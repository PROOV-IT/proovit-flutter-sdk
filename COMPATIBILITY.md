# Compatibility and release validation

## Declared constraints

- Flutter: `>=3.19.0`
- Dart: `>=3.3.0 <4.0.0`
- Android min SDK: 24
- Java bytecode target: 17
- Android compile SDK: 36
- Android Gradle Plugin: 8.6.1 (or compatible newer)
- Kotlin Gradle Plugin: 2.0.21
- CameraX: 1.4.2

## Release environment

Release validation is performed with Flutter 3.38.7, Dart 3.10.7 and OpenJDK 21. The standalone Android project resolves the plugin's native dependencies from Google Maven Central and Flutter's normal repositories. Beta 0.1.0-beta.10 was previously built in an independent Flutter app without manual Gradle changes. Beta 0.1.0-beta.11 changes documentation and release metadata only; it keeps the same SDK runtime and Android dependency versions. The declared lower Flutter constraint is a pubspec compatibility range, not a claim that every historical Flutter/AGP combination was tested.

## Platform support

Android photo/JPEG online capture only. iOS, video, audio and an offline Flutter capture workflow are not implemented in this beta.
