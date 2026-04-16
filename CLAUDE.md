# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

# ReVanced Patch Development Project

## What this project is
A development environment for writing custom ReVanced patches for Android apps,
starting with Instagram. Patches are written in Kotlin using the ReVanced Patcher API.

## Directory structure
```
revanced-development/
  docs/setup-guide.md       # Comprehensive setup and reference guide
  patches/                  # Kotlin/Gradle patches project (from revanced-patches-template)
    patches/src/main/kotlin/app/revanced/patches/
      instagram/            # Instagram patches
      <other-app>/          # Future app patches go in new subdirectories
    extensions/extension/   # Runtime code compiled to DEX, merged into target apps
    settings.gradle.kts     # Applies revanced patches gradle plugin
    gradle.properties       # GitHub Packages auth (gpr.user, gpr.key)
    gradle/libs.versions.toml  # Patcher + plugin version catalog
  workspace/<app>/          # Per-app working directories
    apk/                    # Original APK files
    decompiled/jadx/        # JADX decompiled Java source (readable, for analysis)
    decompiled/apktool/     # APKTool output (resources + Smali)
  tools/                    # Downloaded tool JARs/binaries (gitignored)
  output/                   # Patched APK output (gitignored)
  scripts/
    config.sh               # Central version config for ALL tools and dependencies
    setup-tools.sh           # Download/update tools (reads versions from config.sh)
    decompile.sh             # Decompile APK with JADX + APKTool
    build.sh                 # Build patches and apply to APK
    install.sh               # Install patched APK on device (handles split APKs automatically)
    pull-apk.sh              # Pull APK from device
    package.sh               # Merge patched + split APKs into a single universal APK
```

## Upgrading dependencies
All tool and dependency versions are managed in `scripts/config.sh`. To upgrade:
1. Edit versions in `scripts/config.sh` (JADX, CLI, APKTool, signer, APKEditor, Gradle, patcher, smali)
2. Run `./scripts/setup-tools.sh` to re-download tools and sync Gradle config files
3. Run `cd patches && ./gradlew build` to verify compatibility

## Key commands
- `./scripts/setup-tools.sh` -- download/update all tools, sync Gradle dependency versions
- `./scripts/pull-apk.sh <package> <app-name>` -- pull APK from device
- `./scripts/pull-apk.sh --file <path> <app-name>` -- import a local .apk/.apkm/.xapk/.apks (bundles auto-extracted, split naming normalized). Add `--force` to overwrite existing APKs in the target dir
- `./scripts/decompile.sh <apk-path> <app-name>` -- decompile with JADX + APKTool
- `./scripts/build.sh <app-name>` -- build patches and apply to APK
- `./scripts/install.sh <apk-path>` -- install patched APK on device (auto-includes split APKs, re-signs all)
- `./scripts/package.sh <app-name>` -- merge patched + splits into a single universal APK for sharing

## How patches work
Patches use the `bytecodePatch {}` Kotlin DSL. They find target methods using
structural matching (strings, opcodes, types) rather than names (which are obfuscated).
See `docs/setup-guide.md` for detailed examples.

## When writing patches
- Declare matches at file level using `val BytecodePatchContext.myMatch by composingFirstMethod { }` (or in a separate `Matching.kt`)
- Pass string constants as args to `composingFirstMethod("str")` for fast index-based lookup
- Match on `returnType()`, `parameterTypes()`, `accessFlags()`, `opcodes()`, `strings()`, `instructions {}`, `custom {}` -- not class/method names
- Use `apply { }` block inside `bytecodePatch { }` -- the older `execute { }` is deprecated
- Access matched methods via `myMatch.method` (delegate property on `BytecodePatchContext`)
- Access matched classes via `myMatch.classDef` (for field iteration, etc.)
- Access instruction indices via `myMatch[0]` or `myMatch.indices` (for precise injection)
- Use `myMatch.method.addInstructions(index, smaliCode)` to inject bytecode
- Required imports for Matching.kt: `app.revanced.patcher.composingFirstMethod`, `app.revanced.patcher.patch.BytecodePatchContext`, plus each DSL method used (`app.revanced.patcher.accessFlags`, `.returnType`, `.parameterTypes`, `.opcodes`, `.strings`, `.custom`)
- Required imports for patches: `app.revanced.patcher.patch.bytecodePatch`, `app.revanced.patcher.extensions.addInstructions`
- For simple disable: use `returnEarly()` / `returnEarly(false)` utilities (see revanced-patches for examples)
- For runtime logic, add extension code in `extensions/extension/src/main/java/`
- Reference extensions in Smali as `Lapp/revanced/extension/instagram/ClassName;`
- Test patches by running `./scripts/build.sh <app> && ./scripts/install.sh output/<app>-<version>-patched.apk`
- Convention: one `Matching.kt` + one `SomePatch.kt` per feature directory
- Don't reference obfuscated identifiers (obfuscated class names like `C6KD`, method names like `A02`/`Dvu`, field refs like `c231558wZ.A03`), mobile-config long literals, or decompiled line numbers in code comments or doc blocks. Use semantic descriptions instead ("the V2 insertion helper", "the prefetch trigger"), plus Java framework types (`System.currentTimeMillis`, `Collections.singleton`) and stable in-binary strings (systrace labels, endpoint paths, debug-log strings) that actually appear at the match anchors

## When analyzing decompiled code
- Decompiled Java source is in `workspace/<app>/decompiled/jadx/sources/`
- Resources (layouts, strings) are in `workspace/<app>/decompiled/jadx/resources/`
- Search for UI elements in layout XMLs, string resources, and Java source
- Instagram is heavily obfuscated -- look for string constants and API patterns, not names

## Environment
- WSL2 (Fedora Remix) on Windows
- JDK 17+ required (JDK 21 recommended on Fedora 42)
- ADB connected via WiFi (wireless debugging)
- Device: Pixel 9 Pro XL (non-rooted, Android 15+)
