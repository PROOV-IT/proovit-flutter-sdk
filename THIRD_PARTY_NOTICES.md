# Third-party notices

The Flutter SDK package does not bundle AndroidX or CameraX binaries. Gradle resolves these direct Android dependencies from the standard Google Maven repository:

- `androidx.activity:activity-ktx:1.10.1`
- `androidx.appcompat:appcompat:1.7.0`
- `androidx.core:core-ktx:1.15.0`
- `androidx.camera:camera-camera2:1.4.2`
- `androidx.camera:camera-lifecycle:1.4.2`
- `androidx.camera:camera-video:1.4.2`
- `androidx.camera:camera-view:1.4.2`
- `androidx.exifinterface:exifinterface:1.3.7`

The published POM metadata for each listed direct artifact identifies the Apache License, Version 2.0. The artifacts and their transitive dependencies remain subject to their respective upstream licenses and notices. This notice does not relicense them. Consult the corresponding POM and source distribution for the complete dependency tree and notices used by your build.

The `android/capture-core` Kotlin source is PROOV-IT code and is governed by the repository's [LICENSE](LICENSE).
