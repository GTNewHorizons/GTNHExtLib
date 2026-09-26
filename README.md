# GTNHExtLib

Slowmoving external NH deps

Currently bundled:
- `it.unimi.dsi:fastutil` (Apache 2.0)
- `org.joml:joml` (MIT)
- `com.mojang:brigadier` (MIT)
- `xyz.wagyourtail.jvmdowngrader:jvmdowngrader-java-api` (LGPL 2.1, downgraded-8 + downgraded-17 variants)

## Distribution variants

- **Slim** primary download: only the dep manifest + DepLoader runtime. DepLoader downloads bundled deps from Maven on first launch. Smaller, requires network.
- **Fat** embeds deps. Offline-capable.
- **Offline GTNH** (`gtnhextlib-<version>-gtnh.jar`): fat distribution with
  JvmDowngrader classes, methods and fields minimized for a specific pack build.

Install only one variant. The offline GTNH JAR replaces the normal ExtLib JAR in
the pack. Keep the normal artifacts for development, other packs and added mods.

### Building the offline GTNH variant

The build requires Java 8 and Java 17 usage reports from the same DAXXL pack build:

```sh
./gradlew gtnhOfflineJar \
  -PjvmdgUsageReport8=/path/to/jvmdg-usage-java8.json \
  -PjvmdgUsageReport17=/path/to/jvmdg-usage-java17.json
```

The result is written to `build/libs/`. Ordinary `build` and `assemble` still
produce the regular artifacts; the offline variant is opt-in and is not published
by the tagged-release workflow.

Static analysis is not proof of runtime compatibility or protection against
antivirus detections. Test the client and server on both Java targets, and
regenerate the reports after mod updates.

### CI daily reports

The **Build and test** workflow uses reports from the latest successful DAXXL daily
on `master`. To use a specific daily, run the workflow manually and set
`daxxl-run-id`. Download `gtnhextlib-<version>-gtnh.jar` from the run's artifacts;
the accompanying `gtnh-minimization-reports` artifact contains the reports and
audit output used to produce it.
