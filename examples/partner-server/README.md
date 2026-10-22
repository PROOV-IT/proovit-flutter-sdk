# Partner backend example (Node.js 20+, no dependencies)

This example is deliberately fail-closed. `createPartnerServer()` cannot be constructed without the following host-owned adapters:

- `authenticateHostUser(req)` verifies the partner's own credential/session and returns the authenticated host user or `null`.
- `resolveAuthorizedGrant(hostUser)` loads an explicit, server-side authorization mapping and returns `{ authorized: true, companyId, proovItServiceActorId, mappingId, partnerSubject, scopes }`. `partnerSubject` must be a stable opaque pseudonym scoped to this client/tenant; never use email, phone, or raw PII. Never derive these fields from request JSON, query parameters or a mobile-supplied actor ID.
- `auditMapping(event)` durably records the human host identity, PROOV-IT service actor, tenant, mapping reference and scopes before issuing a session. Audit failure blocks issuance.
- `recordSessionBinding(event)` stores the host user/mapping and a SHA-256 fingerprint of the short-lived bearer with its expiry (never the raw token).
- `authorizeSessionForHost({ hostUserId, tokenSha256 })` verifies the bearer fingerprint is still active and belongs to the currently authenticated host user before proxying capabilities.

The mapped PROOV-IT actor is a **technical service account**. A capture's signed `user_id` remains that technical actor under the unchanged #221 protocol; the server additionally binds its key, challenge and capture rows to the delegated subject. This does not authenticate or attest the human holding the phone. The partner must authenticate that person, authorize the operation and retain its own audit. A PROOV-IT service actor must not be presented as a personal signature.

`PROOVIT_CLIENT_SECRET` stays in this backend's environment. The example applies a server-side scope ceiling, ignores caller-provided actor/company/scopes, returns a short-lived session only after authorization, sets `Cache-Control: no-store`, and binds the bearer fingerprint to the authenticated host user. Re-authenticate and request a fresh session after expiry or account/tenant change; do not persist the secret, raw bearer or logs. The binding/audit adapters must use durable partner storage in a multi-process deployment.

## Reproducible local checks

Requires only Node.js 20 or newer; no install or lockfile is needed.

```sh
npm test
```

The test suite checks anonymous denial, unmapped-user denial, server-side actor/scope mapping, audit-before-issuance, and authenticated capability proxying. It does not contact PROOV-IT staging; connect the required adapters to your actual identity and durable storage services before using this example as a partner backend.

## Wire into a partner host

Construct the server with that host's verified authentication, authorization directory and durable audit adapters, then bind it behind the host's normal TLS/reverse-proxy policy. Copy `.env.example` to a private environment file, replace the placeholders with credentials issued for the authorized partner, and set only the smallest required scope ceiling. Do not expose a permissive development identity adapter in a production deployment. The device/app must never receive the partner client secret.

The SDK bearer is not an `auth.any` user token and cannot call historical `/api/v1` or `/api/v2` workflows. Provisioning requests use the additive `/api/sdk/v1/capture-security/*` routes and the same short-lived bearer. User identity remains the partner's responsibility; the backend SDK actor remains the mapped PROOV-IT service account. Delegation is opt-in for an allow-listed client and tenant. The current delegated bearer is limited to capture-security provisioning and standalone capture/receipt/media; proof and operation routes fail closed pending their resource-binding implementation. Revoke the delegated subject server-side to invalidate its extant bearer sessions.
