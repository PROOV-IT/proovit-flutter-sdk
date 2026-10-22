# PROOV-IT Flutter Capture SDK

Private beta Flutter plugin for Android photo capture with CameraX, P-256 signing in Android Keystore, upload to the PROOV-IT Capture V2 API, and authenticated receipt retrieval.

## Install from the private Git repository

Your GitHub user or organization must have read access to `PROOV-IT/proovit-flutter-sdk`.

```yaml
dependencies:
  proovit_capture:
    git:
      url: git@github.com:PROOV-IT/proovit-flutter-sdk.git
      ref: v0.1.0-beta.9
```

Then run `flutter pub get`. The Android capture core is embedded in the tagged package snapshot. Host applications do not edit `settings.gradle`, declare `:capture-core`, or configure another Maven repository.

### Read-only GitHub access

A repository owner adds the integrator's GitHub user or team with **Read** permission in the private repository's **Settings → Collaborators and teams**. The integrator must authenticate Git on every developer workstation and CI runner that fetches the package.

- **SSH:** register the integrator's public SSH key with their GitHub account, load its private key into `ssh-agent`, and keep the `git@github.com:PROOV-IT/proovit-flutter-sdk.git` URL above.
- **HTTPS:** replace the dependency URL with `https://github.com/PROOV-IT/proovit-flutter-sdk.git`; authenticate with Git Credential Manager or `gh auth login --git-protocol https`. For CI, grant the job a credential with read-only Contents access to this repository.
- Verify the account can read the immutable tag with `git ls-remote <same-repository-url> refs/tags/v0.1.0-beta.9`, then run `flutter pub get`. A 404 without GitHub authentication is expected for this private repository.

Do not put a token or password in the Git URL, `pubspec.yaml`, source code, build arguments or logs. Use the Git credential helper or the CI platform's secret store and grant only read access. Commit the generated `pubspec.lock` in applications to pin the resolved Git commit.

## Platform requirements

- Flutter 3.19+ / Dart 3.3+ (package constraints); release validation uses the exact toolchain in `COMPATIBILITY.md`.
- Android `minSdk 24`, Java 17, Android Gradle Plugin 8.6.1 or newer, compile SDK 36.
- Android only; iOS is not implemented.

## Quickstart

The host application authenticates its own user and provides `issuePartnerSession`, a function that calls the partner's authenticated HTTPS backend and returns an `SdkSession`. The independent app in [`examples/flutter_partner_ready`](examples/flutter_partner_ready) includes a working HTTP implementation of that function and the Android host project. Replace its example backend URL with your own authenticated endpoint; the endpoint must map the signed-in host user to an authorized company and actor on the server.

This function is a complete SDK-side capture sequence; pass the host application's `SessionProvider` implementation. The same compiling example is in [`examples/flutter_partner_ready/lib/quickstart.dart`](examples/flutter_partner_ready/lib/quickstart.dart):

```dart
import 'package:proovit_capture/proovit_capture.dart';

Future<void> captureOne(SessionProvider issuePartnerSession) async {
  final client = ProovitCaptureClient(
    baseUrl: Uri.parse('https://api.staging.proov-it.online/api/'),
    sessionProvider: issuePartnerSession,
  );
  try {
    final capability = await client.getCapabilities();
    final nativeCapture = capability.values['capture.native_signed'];
    if (nativeCapture is! Map || nativeCapture['enabled'] != true ||
        nativeCapture['available_now'] == false) {
      throw StateError('Signed capture is unavailable for this company');
    }

    await client.enrollDeviceIfNeeded();
    final draft = await client.capturePhoto();
    final receipt = await client.submitCapture(draft);
    if (receipt.captureId != draft.captureId ||
        receipt.sha256.toLowerCase() != draft.sha256.toLowerCase() ||
        receipt.sizeBytes != draft.sizeBytes ||
        !['verified', 'verified_degraded'].contains(receipt.status)) {
      throw StateError('The API receipt does not match the captured media');
    }
  } finally {
    client.dispose();
  }
}
```

`SessionProvider` is the SDK's `Future<SdkSession> Function({required bool forceRefresh})` type. Implement it by making an authenticated request to your backend and mapping its response into `SdkSession`; never call the PROOV-IT credential exchange from the device. The partner backend section and sample provide that boundary. This API flow requires an online session, camera permission, and an available Android Activity.

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

Version `0.1.0-beta.9`, immutable Git tag `v0.1.0-beta.9`. The Android core snapshot is generated from canonical `packages/capture-core/android/src/main` by `scripts/export-flutter-sdk.sh`; use its `--check` mode to verify that the packaged core has not diverged. Package checksums are in `SHA256SUMS`. This package is not published on pub.dev or Maven. Read `CHANGELOG.md`, `COMPATIBILITY.md` and `LICENSE` before distributing it.

## Beta boundaries

Supported: Android photo/JPEG, online challenge and API receipt observation. Not provided by this Flutter beta: iOS, video/audio, offline capture, a server-signed receipt, hardware-attestation or app-integrity certification, qualified timestamping, eIDAS certification, or a guaranteed legal outcome. Account capabilities, limits, price and retention vary by partner contract; read the live API response.
