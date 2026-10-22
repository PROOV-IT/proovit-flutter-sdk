# PROOV-IT Flutter Capture SDK

> **⚠️ BETA — SDK EN PHASE DE TEST**
>
> This SDK is currently offered as a beta. Interfaces and features may change before the stable release.
>
> Available today: online Android/JPEG photo capture, subject to the capabilities and permissions of your PROOV-IT account.

The SDK integrates PROOV-IT Capture V2 into an existing Flutter Android app. It handles the native CameraX capture, P-256 signing with Android Keystore, upload and receipt retrieval. Your application keeps its own sign-in flow; your backend authorizes the user and supplies a short-lived SDK session.

## Requirements

| Component | Requirement |
| --- | --- |
| Flutter | 3.19 or newer |
| Dart | 3.3 or newer, below 4.0 |
| Android | `minSdk 24`, `compileSdk 36` |
| Java | 17 bytecode target |
| Android Gradle Plugin | 8.6.1 or newer compatible version |

These are package constraints and the Android configuration in the beta. The release build was validated with Flutter 3.38.7, Dart 3.10.7, OpenJDK 21, AGP 8.6.1 and Kotlin Gradle Plugin 2.0.21. See [COMPATIBILITY.md](COMPATIBILITY.md). iOS is not implemented.

## Install in a few minutes

Add the public Git repository to your app's `pubspec.yaml`:

```yaml
dependencies:
  proovit_capture:
    git:
      url: https://github.com/PROOV-IT/proovit-flutter-sdk.git
      ref: v0.1.0-beta.11
```

Then resolve packages and run the example checks:

```sh
flutter pub get
flutter analyze
flutter test
flutter build apk --debug
```

The package contains its Android capture-core source snapshot. Flutter/Gradle resolves AndroidX and CameraX through the standard repositories; your app does not need to edit `settings.gradle`, add a `:capture-core` project, configure a private Maven repository or use the monorepo. Commit the app's generated `pubspec.lock` to pin the resolved Git commit.

## Flutter quickstart

Pass a `SessionProvider` that calls your own authenticated backend. The function below shows the complete online capture flow and checks that the API receipt describes the captured media:

```dart
import 'package:proovit_capture/proovit_capture.dart';

Future<void> captureOne(SessionProvider issuePartnerSession) async {
  final client = ProovitCaptureClient(
    baseUrl: Uri.parse('https://api.staging.proov-it.online/api/'),
    sessionProvider: issuePartnerSession,
  );
  try {
    final capability = await client.getCapabilities();
    final capture = capability.values['capture.native_signed'];
    if (capture is! Map || capture['enabled'] != true ||
        capture['available_now'] == false) {
      throw StateError('Signed capture is not available for this account');
    }

    await client.enrollDeviceIfNeeded();
    final draft = await client.capturePhoto(); // CameraX + Android Keystore
    final receipt = await client.submitCapture(draft); // upload + receipt GET
    if (receipt.captureId != draft.captureId ||
        receipt.sha256.toLowerCase() != draft.sha256.toLowerCase() ||
        receipt.sizeBytes != draft.sizeBytes ||
        !['verified', 'verified_degraded'].contains(receipt.status)) {
      throw StateError('The server receipt does not match the captured photo');
    }
  } on SdkError catch (error) {
    // Use statusCode / reasonCode to choose a user action; see Error handling.
    rethrow;
  } finally {
    client.dispose();
  }
}
```

`SessionProvider` has type `Future<SdkSession> Function({required bool forceRefresh})`. A complete sample app, including a partner-backend session request, is in [`examples/flutter_partner_ready`](examples/flutter_partner_ready); the SDK sequence is also in [`quickstart.dart`](examples/flutter_partner_ready/lib/quickstart.dart). Replace the sample endpoint with your own HTTPS backend. The app example is not a PROOV-IT login service and contains no partner credentials.

## Partner backend and sessions

Keep the PROOV-IT `client_secret` on your server. Authenticate the person with your application's normal identity provider, map that person to an allowed PROOV-IT company, actor and scopes, then exchange credentials server-to-server for a short-lived SDK session. Return only the scoped bearer and session fields to the app with `Cache-Control: no-store`. Do not trust company, actor or scope values supplied by the mobile client.

The [Node.js 20 partner-server example](examples/partner-server) is a fail-closed integration template. It requires your host authentication, grant lookup, durable audit and session-binding adapters; its tests do not connect to PROOV-IT staging. Connect those interfaces to your services before deploying it. Read the [partner server guide](examples/partner-server/README.md) for request and response shapes.

## Capabilities, capture and receipt

Before capture, read capabilities and check both `enabled` and `available_now` for `capture.native_signed`. Capabilities, user permissions, quotas, costs and retention are set for the authorized account and can change. The API session binds the company, actor and delegated identity scope; do not allow an app screen to select another tenant by changing request fields.

The supported online flow is: obtain an authorized session → read capabilities → enroll or reuse the device key → request a one-time challenge → take a CameraX JPEG → sign with the app's Android Keystore key → upload → retrieve the API receipt. `submitCapture` compares returned identity/capture fields, media digest and size, MIME type, key and challenge bindings, and receipt status with the captured draft. The receipt is observed and checked against API fields; it is not independently signed by the server.

## Errors, retry and offline behavior

- `401`: session missing, expired or revoked. Refresh through your backend; the SDK performs one refresh for a GET that returns 401.
- `402`: insufficient balance or entitlement. Show the refusal and do not report success.
- `403`: scope or capability denied. Correct the authorization; do not retry in a loop.
- `404`: item absent or outside the caller's identity scope.
- `409`: conflict or capture reconciliation required. Keep the same capture ID and original bytes, inspect the receipt/reconciliation state, then decide whether to retry.
- `422`: rejected media, signature, challenge or payload. Correct the cause before taking a new capture.
- `503`: temporary unavailability. Use bounded backoff only for idempotent operations.

`listPending()` and `resumePending()` expose locally journaled captures for the current identity scope. Keep an app retention/deletion policy for pending media. Upload replay is not blindly performed because it may not be idempotent. A new capture requires network access, camera permission, an available Android Activity and local disk space. Offline capture is not exposed by this Flutter beta.

## Security and license

Never put a PROOV-IT `client_secret`, permanent credential, or long-lived user token in Dart code, an APK, a build argument, OTA, source repository or logs. Use HTTPS for the partner session endpoint and return only a short-lived, least-privilege SDK bearer. Keep session state out of persistent mobile storage unless your threat model and revocation design explicitly support it.

This public source repository is **not an open-source grant**. [LICENSE](LICENSE) permits beta evaluation and partner integration under PROOV-IT's stated conditions; redistribution and production use require separate written authorization or the applicable partner agreement. The AndroidX/CameraX artifacts are resolved from Google Maven rather than copied into this repository; their direct dependencies declare Apache License 2.0. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Feature status

| Feature | Beta status |
| --- | --- |
| Android CameraX photo capture, JPEG, online challenge | Available, subject to account capability and permission |
| P-256 signing with Android Keystore | Available on supported Android devices |
| Upload and API receipt retrieval/comparison | Available; receipt is not server-signed |
| Local pending-capture journal and resume API | Available; app owns retention policy |
| iOS | Not supported |
| Video or audio | Not supported |
| Offline capture through Flutter | Not supported |
| Certified hardware attestation or app-integrity claim | Not provided |
| Qualified timestamp or eIDAS certification | Not provided |

## API documentation and environments

- [PROOV-IT developer guide](https://admin.staging.proov-it.online/developers/sdk)
- [Capture V2 OpenAPI 3.1](https://admin.staging.proov-it.online/developers/sdk/openapi.yaml)
- Staging API base: `https://api.staging.proov-it.online/api/`

The documentation Swagger UI defaults to staging and has “Try it out” disabled. The partner's own authentication endpoint is not part of the PROOV-IT API contract.

## Versioning, changelog and support

Pin an immutable `v0.1.0-beta.N` tag (or its full commit SHA); do not depend on a moving branch. Verify release files with `sha256sum -c SHA256SUMS`. See [CHANGELOG.md](CHANGELOG.md) for changes, [RELEASE-METADATA.md](RELEASE-METADATA.md) for this release and [COMPATIBILITY.md](COMPATIBILITY.md) for the validated toolchain. Do not publish this package to pub.dev or Maven unless PROOV-IT announces that channel.

For a bug report, open an issue in this repository with the SDK tag, Flutter/Dart versions, Android API/device model, a redacted error code and reproducible steps. Remove bearer tokens, partner identifiers, photos, private URLs and personal data from logs before sharing them.
