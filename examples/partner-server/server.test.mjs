import assert from 'node:assert/strict';
import { afterEach, test } from 'node:test';
import { createPartnerServer } from './server.mjs';

const env = {
  PROOVIT_API_BASE_URL: 'https://synthetic.proov-it.invalid/api',
  PROOVIT_CLIENT_ID: 'synthetic-client',
  PROOVIT_CLIENT_SECRET: 'synthetic-server-secret',
  PROOVIT_SCOPES: 'capture.native_signed,capture.challenge_online',
};
const running = [];

async function listen(server) {
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  running.push(server);
  return `http://127.0.0.1:${server.address().port}`;
}

afterEach(async () => {
  await Promise.all(running.splice(0).map((server) => new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()))));
});

function response(status, body) {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
}

test('fails closed when host authentication, authorized mapping, or audit adapters are omitted', () => {
  assert.throws(() => createPartnerServer({ env }), /adapters are required/);
});

test('rejects anonymous and authenticated-but-unmapped host users without contacting ProovIT', async () => {
  let upstreamCalls = 0;
  const server = createPartnerServer({
    env,
    authenticateHostUser: async (req) => req.headers.authorization === 'Bearer host-user-token' ? { id: 'host-user-17' } : null,
    resolveAuthorizedGrant: async () => { throw new Error('no authorized mapping'); },
    auditMapping: async () => {},
    recordSessionBinding: async () => {},
    authorizeSessionForHost: async () => false,
    fetchImpl: async () => { upstreamCalls++; return response(201, {}); },
  });
  const base = await listen(server);

  const anonymous = await fetch(`${base}/partner/proov-it/session`, { method: 'POST' });
  assert.equal(anonymous.status, 401);
  assert.equal((await anonymous.json()).error, 'host_authentication_required');
  const unmapped = await fetch(`${base}/partner/proov-it/session`, { method: 'POST', headers: { authorization: 'Bearer host-user-token' } });
  assert.equal(unmapped.status, 403);
  assert.equal((await unmapped.json()).error, 'host_user_not_authorized');
  assert.equal(upstreamCalls, 0);
});

test('issues only server-mapped actor and scopes, audits the host mapping, and exposes capabilities only to an authenticated host', async () => {
  const audits = [];
  const bindings = [];
  const upstreamRequests = [];
  const server = createPartnerServer({
    env,
    authenticateHostUser: async (req) => req.headers.authorization === 'Bearer host-user-token' ? { id: 'host-user-17' } : null,
    resolveAuthorizedGrant: async (user) => user.id === 'host-user-17' ? {
      authorized: true, companyId: 'tenant-uuid', proovItServiceActorId: 'proovit-service-user-id',
      mappingId: 'mapping-audit-42', partnerSubject: 'opaque-partner-subject-0001', scopes: ['capture.native_signed', 'capture.challenge_online'],
    } : null,
    auditMapping: async (event) => audits.push(event),
    recordSessionBinding: async (event) => bindings.push(event),
    authorizeSessionForHost: async ({ hostUserId, tokenSha256 }) => bindings.some((binding) => binding.hostUserId === hostUserId && binding.tokenSha256 === tokenSha256),
    fetchImpl: async (url, options = {}) => {
      upstreamRequests.push({ url, options });
      return url.endsWith('/sdk/v1/sessions')
        ? response(201, { data: { access_token: 'pps_synthetic', token_type: 'Bearer', scope: ['capture.native_signed'], expires_at: '2030-01-01T00:00:00Z' } })
        : response(200, { data: { mode: 'modular', capabilities: {} } });
    },
  });
  const base = await listen(server);
  const issue = await fetch(`${base}/partner/proov-it/session`, {
    method: 'POST',
    headers: { authorization: 'Bearer host-user-token', 'content-type': 'application/json' },
    body: JSON.stringify({ actor_id: 'attacker-chosen-actor', company_id: 'other-tenant', scopes: ['blockchain.anchor'] }),
  });
  assert.equal(issue.status, 201);
  const issued = await issue.json();
  assert.equal(issued.actor_kind, 'partner_service_account');
  assert.equal(issued.access_token, 'pps_synthetic');
  assert.equal(issued.company_id, 'tenant-uuid');
  assert.equal(issued.actor_id, 'proovit-service-user-id');
  assert.match(issued.identity_scope, /^[a-f0-9]{64}$/);
  const body = JSON.parse(upstreamRequests[0].options.body);
  assert.equal(body.actor_id, 'proovit-service-user-id');
  assert.equal(body.company_id, 'tenant-uuid');
  assert.deepEqual(body.scopes, ['capture.native_signed', 'capture.challenge_online']);
  assert.equal(body.client_secret, env.PROOVIT_CLIENT_SECRET);
  assert.deepEqual(body.delegation, { type: 'partner_subject', subject: 'opaque-partner-subject-0001' });
  assert.deepEqual(audits, [{
    hostUserId: 'host-user-17', companyId: 'tenant-uuid', proovItServiceActorId: 'proovit-service-user-id',
    mappingId: 'mapping-audit-42', scopes: ['capture.native_signed', 'capture.challenge_online'],
  }]);
  assert.equal(bindings.length, 1);
  assert.equal(bindings[0].hostUserId, 'host-user-17');
  assert.equal(bindings[0].mappingId, 'mapping-audit-42');
  assert.equal(bindings[0].scopes.includes('blockchain.anchor'), false);

  const anonymousCapabilities = await fetch(`${base}/partner/proov-it/capabilities`, { headers: { authorization: 'Bearer pps_synthetic' } });
  assert.equal(anonymousCapabilities.status, 401);
  const otherHostUser = await fetch(`${base}/partner/proov-it/capabilities`, {
    headers: { authorization: 'Bearer other-host-user', 'x-sdk-session': 'pps_synthetic' },
  });
  assert.equal(otherHostUser.status, 401);
  const authorizedCapabilities = await fetch(`${base}/partner/proov-it/capabilities`, {
    headers: { authorization: 'Bearer host-user-token', 'x-sdk-session': 'pps_synthetic' },
  });
  assert.equal(authorizedCapabilities.status, 200);
  assert.equal(upstreamRequests.at(-1).options.headers.authorization, 'Bearer pps_synthetic');
});

test('fails closed when the partner mapping has no opaque delegated subject', async () => {
  const server = createPartnerServer({
    env,
    authenticateHostUser: async () => ({ id: 'host-user-17' }),
    resolveAuthorizedGrant: async () => ({ authorized: true, companyId: 'tenant', proovItServiceActorId: 'actor', mappingId: 'mapping', scopes: ['capture.native_signed'] }),
    auditMapping: async () => {}, recordSessionBinding: async () => {}, authorizeSessionForHost: async () => false,
    fetchImpl: async () => { throw new Error('should not call upstream'); },
  });
  const base = await listen(server);
  const result = await fetch(`${base}/partner/proov-it/session`, { method: 'POST', headers: { authorization: 'Bearer host' } });
  assert.equal(result.status, 403);
  assert.equal((await result.json()).error, 'authorized_proov_it_actor_mapping_required');
});
