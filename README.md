# GTNHExtLib

Slowmoving external NH deps

Currently bundled:
- `it.unimi.dsi:fastutil` (Apache 2.0)
- `org.joml:joml` (MIT)
- `com.mojang:brigadier` (MIT)
- `xyz.wagyourtail.jvmdowngrader:jvmdowngrader-java-api` (LGPL 2.1, downgraded-8 + downgraded-17 variants)

## Distribution variants

- **Slim** primary download: only the dep manifest + DepLoader runtime. DepLoader downloads bundled deps from Maven on first launch. Smaller, requires network.
- **Fat** embeddeds deps. Offline-capable.
- **Offline GTNH** (`gtnhextlib-<version>-gtnh.jar`): fat distribution with
  JvmDowngrader classes, methods and fields minimized for a specific pack build.

Install only one variant. The offline GTNH JAR replaces the normal ExtLib JAR in
the pack. Keep the normal artifacts for development, other packs and added mods.

### Report-driven offline build

Generate Java 8 and Java 17 reports from the finalized client and server packs
using DAXXL's `jvmdg_usage_report.py`, or download both reports from the same DAXXL
daily run. Use reports for the exact pack being distributed; an older daily can
miss dependencies introduced by newer mods.

```sh
./gradlew gtnhOfflineJar \
  -PjvmdgUsageReport8=/path/to/jvmdg-usage-java8.json \
  -PjvmdgUsageReport17=/path/to/jvmdg-usage-java17.json
```

Both reports are required for minimization and must have the same nonempty
`build_id`. Invalid schemas, wrong Java targets, scan errors and missing root
classes fail the build. References to members absent from the upstream API are
currently warnings, listed in `source-missing-report-members.txt`; investigate
these before distribution. Matching build IDs do not independently prove that
the reports cover the pack you intend to ship.

The task uses upstream JvmDowngrader's dependency graph to retain report roots
and their dependencies. The standalone tool and both API inputs are SHA-256 pinned
in `gradle.properties`. Retained classes are rewritten with unused members removed;
this is not the fixed process/HTTP exclusion profile from `algent/jvmdg-alternate`.

The result is in `build/libs/`. Audits under `build/reports/jvmdg-minimized/java8/`
and `java17/` include retained/removed classes, counts, input report hashes and
output hashes. Embedded API classifiers use `downgraded-<target>-gtnh-min-<sha256>`
in both the dependency manifests and embedded repository. Different minimized
bytes cannot reuse the full upstream API's cache entry or another minimized API's
entry; identical bytes intentionally share an identity.

Keep reports outside `build/` when running `clean`. Ordinary `build` and `assemble`
still produce the regular artifacts without reports. The offline jar is opt-in
and is not automatically published by the tagged-release workflow.

Static analysis is not proof of runtime compatibility or protection against
antivirus detections. DAXXL conservatively scans all multi-release variants;
nested archives and computed reflection are outside its current coverage. Test
client/server startup and representative gameplay on both Java targets before
distribution, with fresh dependency caches. Regenerate reports after mod updates.

### CI daily reports

The **Build and test** workflow builds the offline jar alongside the shared GTNH
build. It defaults to the latest successful DAXXL `daily-modpack-build.yml` run on
`master`. For a specific pack daily, use **Run workflow** and set `daxxl-run-id`.
The explicit run must also be a successful daily from DAXXL's `master` branch.

Both reports are selected from that one run. Missing, ambiguous or expired
artifacts fail the job, with no silent fallback to an older run. The Gradle build
performs report-pair and content validation, and CI verifies the final jar's
dependency manifests against the embedded API hashes.

Download `gtnh-offline-jar` for the candidate jar and `gtnh-minimization-reports`
for reports, audits and `source.json` (DAXXL run ID/attempt, commit, artifact IDs
and digests). These are retained for 90 days. Retain these inputs to reproduce a
build: DAXXL's original artifacts expire after 14 days, and an automatic rerun
selects the latest successful daily again. CI generation does not run pack smoke tests.

Downloads use the workflow token by default. If GitHub denies cross-repository
artifact access, configure `DAXXL_REPORTS_TOKEN` with **Actions: read** on
`GTNewHorizons/DreamAssemblerXXL`. Fork PRs cannot access that secret.

Build-logic regression checks run automatically with `buildSrc`, or explicitly
with `./gradlew :buildSrc:verifyOfflineGtnh`. Report selection tests run with
`node --test .github/scripts/select-daxxl-reports.test.cjs`. Check a packaged jar
with `python3 .github/scripts/check-offline-jar.py /path/to/gtnhextlib-1.0.4-gtnh.jar`.
