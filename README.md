# ReVanced Patch Development

A development environment for writing custom [ReVanced](https://revanced.app/) patches for Android apps. Patches are written in Kotlin using the ReVanced Patcher API and applied to APKs via the ReVanced CLI.

Supports patch development for any number of apps in parallel — each app lives in its own subdirectory under `patches/`, `extensions/`, and `workspace/`. Instagram is included as the first worked example.

## Quick Start

### Prerequisites

- WSL2 with Fedora (or similar Linux)
- JDK 21 (`sudo dnf install java-21-openjdk-devel`)
- ADB (`sudo dnf install android-tools`)
- GitHub account with `gh` CLI configured

See [docs/setup-guide.md](docs/setup-guide.md) for detailed setup instructions.

### Setup

```bash
# 1. Configure GitHub Packages auth (required for Gradle dependencies)
#    Add to ~/.gradle/gradle.properties:
#    gpr.user=<username>  gpr.key=<token>
#    githubPackagesUsername=<username>  githubPackagesPassword=<token>

# 2. Download tools (JADX, APKTool, ReVanced CLI, APKEditor, Android SDK)
./scripts/setup-tools.sh

# 3. Verify the patches project builds
cd patches && ./gradlew build && cd ..

# 4. Connect your Android device via WiFi ADB
adb pair <IP>:<PAIRING_PORT>      # enter 6-digit code from phone
adb connect <IP>:<CONNECTION_PORT>
```

### Development Workflow

```bash
# Add a new app — pulls APK from device and scaffolds the patches/extensions
# directories for the app. Also used to refresh the APK with --force.
./scripts/add-app.sh com.instagram.android instagram
# …or import a manually-downloaded .apk/.apkm/.xapk/.apks (bundles auto-extracted)
./scripts/add-app.sh --file ~/Downloads/instagram.apkm instagram

# Decompile (JADX for readable Java, APKTool for resources/Smali)
./scripts/decompile.sh workspace/instagram/apk/base.apk instagram

# Write patches in patches/patches/src/main/kotlin/app/revanced/patches/instagram/

# Build patches and apply to APK (output includes app version in filename)
./scripts/build.sh instagram

# Install on device (automatically includes split APKs and re-signs everything)
./scripts/install.sh output/instagram-<version>-patched.apk

# Package as a single universal APK for sharing
./scripts/package.sh instagram
```

To start a second app, run `./scripts/add-app.sh <package> <app-name>` with a new `<app-name>`. It creates the patches/extensions subdirectories alongside the existing ones — the rest of the workflow is identical.

## Project Structure

```
patches/                    Kotlin/Gradle patches project
  patches/src/main/kotlin/  Patch source code (one subdir per app)
  extensions/extension/     Runtime code compiled to DEX and merged into target apps
  gradle/libs.versions.toml Patcher + Smali version catalog

workspace/<app>/            Per-app working directories
  apk/                      Original APK files (base + splits)
  decompiled/jadx/          JADX output (readable Java source + resources)
  decompiled/apktool/       APKTool output (Smali + decoded resources)

scripts/
  config.sh                 Central version config for all tools and dependencies
  setup-tools.sh            Download/update tools, sync Gradle versions
  add-app.sh                Scaffold a new app and pull/import its APK (.apk/.apkm/.xapk/.apks)
  decompile.sh              Decompile APK with JADX + APKTool
  build.sh                  Build patches and apply to APK
  install.sh                Install patched APK on device (handles split APKs)
  package.sh                Merge patched + splits into a single universal APK

tools/                      Downloaded tools (gitignored)
output/                     Patched APK output (gitignored)
docs/setup-guide.md         Comprehensive setup and reference guide
```

## Upgrading Dependencies

All versions are centralized in [`scripts/config.sh`](scripts/config.sh):

```bash
# 1. Edit versions in scripts/config.sh
# 2. Re-run setup (downloads new tools + syncs Gradle config)
./scripts/setup-tools.sh
# 3. Verify
cd patches && ./gradlew build
```

Tool versions (JADX, CLI, APKTool, APKEditor, Android SDK) and Gradle dependency versions (Gradle wrapper, patcher, plugin, smali) are all managed from this single file.

## Writing Patches

Patches use the `bytecodePatch {}` Kotlin DSL with `composingFirstMethod` for locating
methods in obfuscated code:

```kotlin
// Matching.kt -- match declarations
import app.revanced.patcher.composingFirstMethod
import app.revanced.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.AccessFlags

// Matches are declared at file level (or in a separate Matching.kt).
// String args are used for fast index-based lookup.
internal val BytecodePatchContext.myMatch by composingFirstMethod(
    "some_unique_string",
) {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("V")
}

// MyPatch.kt -- patch logic
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch

@Suppress("unused")
val myPatch = bytecodePatch(
    name = "My patch",
    description = "Does something useful.",
) {
    compatibleWith("com.instagram.android")

    apply {
        myMatch.method.addInstructions(0, "return-void")
    }
}
```

Matches use structural properties (`strings`, `opcodes`, `returnType`,
`accessFlags`, `parameterTypes`, `instructions`, `custom`) instead of class/method
names, making them resilient to obfuscation.

See [docs/setup-guide.md](docs/setup-guide.md) for the full API reference, extensions
system, and troubleshooting.
