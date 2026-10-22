# Changelog

## 0.1.0-beta.8

- Correct release metadata and compatibility guidance; package contents and capture behavior are unchanged from beta.7.

## 0.1.0-beta.7 — 2026-10-22

- Reuses the authenticated partner session in the independent example instead of minting a second session during connect.

## 0.1.0-beta.6 — 2026-10-22

- Moves plugin metadata below the Gradle `plugins` block for standard Flutter/Gradle script compatibility.

## 0.1.0-beta.5 — 2026-10-22

- Uses the host application’s Android Gradle Plugin and Kotlin plugin versions without loading duplicate plugin classpaths.

## 0.1.0-beta.4 — 2026-10-22

- Caps the independent sample Gradle worker memory for predictable debug builds.

## 0.1.0-beta.3 — 2026-10-22

- Replaces the generated counter smoke test with a quickstart connection-flow test.

## 0.1.0-beta.2 — 2026-10-22

- Corrects the private distribution repository root documentation and release metadata.

## 0.1.0-beta.1 — 2026-10-22

- First private, tag-targeted Flutter Android package.
- Embeds the reproducible Android `capture-core` source snapshot so partner apps need no manual Gradle project mapping.
- Documents online CameraX photo capture, Keystore signature, upload, receipt comparison and current beta limits.
