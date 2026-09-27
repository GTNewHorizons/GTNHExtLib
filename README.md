# GTNHExtLib

Slowmoving external NH deps

Currently bundled:
- `it.unimi.dsi:fastutil` (Apache 2.0)
- `org.joml:joml` (MIT)
- `com.mojang:brigadier` (MIT)
- `com.github.GTNewHorizons:DataFixerUpper-J8` (MIT)
- `xyz.wagyourtail.jvmdowngrader:jvmdowngrader-java-api` (LGPL 2.1, downgraded-8 + downgraded-17 variants)

## Two distribution variants

- **Slim** primary download: only the dep manifest + DepLoader runtime. DepLoader downloads bundled deps from Maven on first launch. Smaller, requires network.
- **Fat** embeddeds deps. Offline-capable.

Installing both side by side is a Forge duplicate-modid error.
