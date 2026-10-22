import { createServer } from 'node:http';
import { createHash } from 'node:crypto';

const CAPTURE_SCOPES = new Set(['capture.native_signed', 'capture.challenge_online', 'capture.offline_lease', 'capture.verify']);

function json(res, status, value) {
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' });
  res.end(JSON.stringify(value));
}

export function createPartnerServer({ env = process.env, authenticateHostUser, resolveAuthorizedGrant, auditMapping, recordSessionBinding, authorizeSessionForHost, fetchImpl = fetch }) {
  if (typeof authenticateHostUser !== 'function' || typeof resolveAuthorizedGrant !== 'function' || typeof auditMapping !== 'function'
    || typeof recordSessionBinding !== 'function' || typeof authorizeSessionForHost !== 'function') {
    throw new TypeError('Host authentication, actor authorization, mapping audit, and session-binding adapters are required.');
  }
  const required = ['PROOVIT_API_BASE_URL', 'PROOVIT_CLIENT_ID', 'PROOVIT_CLIENT_SECRET', 'PROOVIT_SCOPES'];
  for (const key of required) if (!env[key]) throw new Error(`Missing server configuration: ${key}`);
  const apiBaseUrl = env.PROOVIT_API_BASE_URL.replace(/\/$/, '');
  const configuredScopes = env.PROOVIT_SCOPES.split(',').map((scope) => scope.trim()).filter(Boolean);
  if (configuredScopes.some((scope) => !CAPTURE_SCOPES.has(scope))) throw new Error('PROOVIT_SCOPES contains an unsupported example scope.');

  async function authenticate(req, res) {
    try {
      const hostUser = await authenticateHostUser(req);
      if (!hostUser || typeof hostUser.id !== 'string' || hostUser.id.length === 0) {
        json(res, 401, { error: 'host_authentication_required' });
        return null;
      }
      return hostUser;
    } catch {
      json(res, 401, { error: 'host_authentication_failed' });
      return null;
    }
  }

  const server = createServer(async (req, res) => {
    const url = new URL(req.url ?? '/', 'http://localhost');
    if (req.method === 'POST' && url.pathname === '/partner/proov-it/session') {
      const hostUser = await authenticate(req, res);
      if (!hostUser) return;

      let grant;
      try {
        grant = await resolveAuthorizedGrant(hostUser);
      } catch {
        return json(res, 403, { error: 'host_user_not_authorized' });
      }
      if (!grant?.authorized || !grant.companyId || !grant.proovItServiceActorId || !grant.mappingId
        || typeof grant.partnerSubject !== 'string' || !Array.isArray(grant.scopes)) {
        return json(res, 403, { error: 'authorized_proov_it_actor_mapping_required' });
      }
      if (!/^[A-Za-z0-9][A-Za-z0-9._:-]{15,127}$/.test(grant.partnerSubject)) {
        return json(res, 403, { error: 'opaque_partner_subject_required' });
      }
      const scopes = [...new Set(grant.scopes)].filter((scope) => configuredScopes.includes(scope));
      if (scopes.length === 0 || grant.scopes.some((scope) => !CAPTURE_SCOPES.has(scope))) {
        return json(res, 403, { error: 'no_authorized_capture_scope' });
      }
      try {
        await auditMapping({ hostUserId: hostUser.id, companyId: grant.companyId, proovItServiceActorId: grant.proovItServiceActorId, mappingId: grant.mappingId, scopes });
      } catch {
        return json(res, 503, { error: 'actor_mapping_audit_unavailable' });
      }

      let response;
      try {
        response = await fetchImpl(`${apiBaseUrl}/sdk/v1/sessions`, {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify({
            client_id: env.PROOVIT_CLIENT_ID,
            client_secret: env.PROOVIT_CLIENT_SECRET,
            company_id: grant.companyId,
            actor_id: grant.proovItServiceActorId,
            scopes,
            delegation: { type: 'partner_subject', subject: grant.partnerSubject },
          }),
        });
      } catch {
        return json(res, 503, { error: 'proov_it_unavailable' });
      }
      const body = await response.json().catch(() => ({}));
      if (!response.ok) return json(res, response.status, { error: body.code ?? 'session_request_failed' });
      const session = body.data;
      const expiresAt = Date.parse(session?.expires_at ?? '');
      if (typeof session?.access_token !== 'string' || !session.access_token.startsWith('pps_')
        || session.token_type !== 'Bearer' || !Array.isArray(session.scope)
        || session.scope.some((scope) => !scopes.includes(scope)) || !Number.isFinite(expiresAt) || expiresAt <= Date.now()) {
        return json(res, 502, { error: 'invalid_sdk_session_response' });
      }
      try {
        await recordSessionBinding({
          hostUserId: hostUser.id, mappingId: grant.mappingId, companyId: grant.companyId,
          proovItServiceActorId: grant.proovItServiceActorId,
          tokenSha256: createHash('sha256').update(session.access_token).digest('hex'),
          scopes: session.scope, expiresAt: new Date(expiresAt).toISOString(),
        });
      } catch {
        return json(res, 503, { error: 'session_binding_unavailable' });
      }
      const identityScope = createHash('sha256')
        .update(`${hostUser.id}\n${grant.companyId}\n${grant.mappingId}\n${grant.partnerSubject}`)
        .digest('hex');
      return json(res, 201, {
        ...session,
        actor_kind: 'partner_service_account',
        company_id: grant.companyId,
        actor_id: grant.proovItServiceActorId,
        identity_scope: identityScope,
      });
    }

    if (req.method === 'GET' && url.pathname === '/partner/proov-it/capabilities') {
      const hostUser = await authenticate(req, res);
      if (!hostUser) return;
      const token = typeof req.headers['x-sdk-session'] === 'string' ? req.headers['x-sdk-session'] : null;
      if (!token) return json(res, 401, { error: 'sdk_session_required' });
      let authorized;
      try {
        authorized = await authorizeSessionForHost({
          hostUserId: hostUser.id,
          tokenSha256: createHash('sha256').update(token).digest('hex'),
        });
      } catch {
        return json(res, 403, { error: 'sdk_session_not_authorized_for_host_user' });
      }
      if (!authorized) return json(res, 403, { error: 'sdk_session_not_authorized_for_host_user' });
      try {
        const response = await fetchImpl(`${apiBaseUrl}/sdk/v1/capabilities`, { headers: { authorization: `Bearer ${token}` } });
        return json(res, response.status, await response.json().catch(() => ({})));
      } catch {
        return json(res, 503, { error: 'proov_it_unavailable' });
      }
    }

    return json(res, 404, { error: 'not_found' });
  });

  return server;
}

// This module intentionally has no permissive standalone mode. A partner must supply
// its own real authentication, authorization/mapping, and audit adapters before binding it.
