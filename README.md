# PROOV-IT Flutter Capture SDK

Private beta Flutter plugin for Android photo capture with CameraX, P-256 signing in Android Keystore, upload to the PROOV-IT Capture V2 API, and authenticated receipt retrieval.

## Install from the private Git repository

Your GitHub user or organization must have read access to `PROOV-IT/proovit-flutter-sdk`.

```yaml
dependencies:
  proovit_capture:
    git:
      url: git@github.com:PROOV-IT/proovit-flutter-sdk.git
      ref: v0.1.0-beta.8
```

Then run `flutter pub get`. The Android capture core is embedded in the tagged package snapshot. Host applications do not edit `settings.gradle`, declare `:capture-core`, or configure another Maven repository. Use your normal GitHub SSH access for private dependencies. Never put a GitHub token or partner API secret in `pubspec.yaml`, source code, or build arguments.

## Platform requirements

- Flutter 3.19+ / Dart 3.3+ (package constraints); release validation uses the exact toolchain in `COMPATIBILITY.md`.
- Android `minSdk 24`, Java 17, Android Gradle Plugin 8.6.1 or newer, compile SDK 36.
- Android only; iOS is not implemented.

## Quickstart

The partner backend authenticates the host user and mints a short-lived PROOV-IT SDK session. The app passes a session provider to the SDK; never call the partner credential exchange from the device.

```dart
final client = ProovitCaptureClient(
  baseUrl: Uri.parse('https://api.staging.proov-it.online/api/'),
  sessionProvider: ({required forceRefresh}) => partnerApi.issueCaptureSession(
    forceRefresh: forceRefresh,
  ),
);

final capability = await client.getCapabilities();
final nativeCapture = capability.values['capture.native_signed'];
if (nativeCapture is! Map || nativeCapture['enabled'] != true ||
    nativeCapture['available_now'] == false) {
  throw StateError('Signed capture is not available for this company');
}
await client.enrollDeviceIfNeeded();
final draft = await client.capturePhoto();
final receipt = await client.submitCapture(draft,
  onProgress: (sent, total) => reportProgress(total == 0 ? 0 : sent / total));
assert(receipt.captureId == draft.captureId);
assert(receipt.sha256.toLowerCase() == draft.sha256.toLowerCase());
assert(receipt.sizeBytes == draft.sizeBytes);
assert(receipt.status == 'verified' || receipt.status == 'verified_degraded');
client.dispose();
```

`partnerApi.issueCaptureSession` calls an authenticated endpoint on your backend and constructs:

```dart
SdkSession(
  accessToken: json['access_token'] as String,
  expiresAt: DateTime.parse(json['expires_at'] as String),
  companyId: json['company_id'] as String,
  actorId: json['actor_id'] as String,
  identityScope: json['identity_scope'] as String,
)
```

Use a stable opaque `identityScope` for each authorized user/tenant/delegated-subject boundary. Clear cached sessions, clients, capabilities and receipt UI on logout or account/company change. The SDK partitions local keys and pending captures by this scope.

## Partner backend

The [`examples/partner-server`](examples/partner-server) Node.js 20 example is dependency-free and fail-closed. It requires host-authentication, server-side grant mapping, durable audit and session-binding adapters. Its tests exercise these contracts; implement the adapters against your actual services before deploying it. The PROOV-IT `client_secret` stays in the backend. Ignore client-supplied company, actor and scope fields, and keep the returned SDK bearer short-lived and `Cache-Control: no-store`.

The [`examples/flutter_partner_ready`](examples/flutter_partner_ready) app is an independent Flutter host application consuming only the Git SDK dependency. It contains no private partner credentials or staging-only login flow.

## Capture, receipt and recovery

The supported online path is capabilities → key lookup/enrollment → one-time online challenge → CameraX JPEG → Keystore signature and durable app-private journal → multipart upload → receipt GET and field comparison. `submitCapture` checks capture/company IDs, digest, length, MIME, device key, signed payload fields and the signature/media/challenge/binding/retention values returned by the API. It reports verified API fields; the current API receipt is not cryptographically signed by the server.

`listPending()` and `resumePending()` expose and submit recoverable captures for the current identity scope. After an ambiguous upload or `409 capture_reconciliation_required`, keep the same capture and original bytes, then inspect the receipt or reconcile before retrying. Set a local retention/deletion policy for pending media. Do not retry non-idempotent or billable operations without checking their response and contract.

This Flutter beta supports online challenges only. The server protocol and Android core have offline-lease primitives, but this plugin does not expose a supported offline capture API. A new capture needs camera permission, an available Android Activity, disk space and a live API session.

## API and errors

See the public [developer guide](https://admin.staging.proov-it.online/developers/sdk) and [OpenAPI 3.1 document](https://admin.staging.proov-it.online/developers/sdk/openapi.yaml). Staging API base URL: `https://api.staging.proov-it.online/api/`.

- `401`: session missing, expired or revoked; authenticate through the partner backend.
- `402`: insufficient balance or entitlement; display the refusal and do not claim success.
- `403`: scope/capability denied; don't retry in a loop.
- `404`: object absent or hidden outside this identity scope.
- `409`: collision or reconciliation required; preserve the same capture ID and bytes.
- `422`: payload, signature, challenge or media rejected; fix the source before a new attempt.
- `503`: service unavailable; bounded backoff only for idempotent operations.

The client makes one session refresh after a GET returns 401. It does not blindly replay every capture upload.

## Version and source provenance

Version `0.1.0-beta.8`, immutable Git tag `v0.1.0-beta.8`. The Android core snapshot is generated from canonical `packages/capture-core/android/src/main` by `scripts/export-flutter-sdk.sh`; use its `--check` mode to verify that the packaged core has not diverged. Package checksums are in `SHA256SUMS`. This package is not published on pub.dev or Maven. Read `CHANGELOG.md`, `COMPATIBILITY.md` and `LICENSE` before distributing it.

## Beta boundaries

Supported: Android photo/JPEG, online challenge and API receipt observation. Not provided by this Flutter beta: iOS, video/audio, offline capture, a server-signed receipt, hardware-attestation or app-integrity certification, qualified timestamping, eIDAS certification, or a guaranteed legal outcome. Account capabilities, limits, price and retention vary by partner contract; read the live API response.
