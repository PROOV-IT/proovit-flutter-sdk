# PROOV-IT Flutter partner quickstart

Independent Android application generated with `flutter create`, consuming only the public SDK Git dependency. It uses only the partner’s own authenticated session endpoint and includes no credentials. See the public staging documentation for partner backend setup and session setup.

```sh
flutter pub get
flutter analyze
flutter test
flutter build apk --debug
```

Any developer can clone the public `PROOV-IT/proovit-flutter-sdk` repository and run those commands without adding a Gradle project or editing `settings.gradle`. Configure the app with the partner's own authenticated session endpoint and a host bearer. The endpoint returns the short-lived SDK session created by the partner backend; it must never return the permanent `client_secret`.

For hardware validation, install the debug build on a supported Android phone, connect to the real partner endpoint and staging company, grant camera access, and run the capture flow. This repository does not include partner credentials.
