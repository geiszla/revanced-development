# ReVanced Patch Development -- Setup Guide

> Last updated: 2026-03-27

## Table of Contents

- [Overview](#overview)
- [Repositories](#repositories)
- [External Requirements](#external-requirements)
- [VSCode / Antigravity Extensions](#vscode--antigravity-extensions)
- [Setup Guide](#setup-guide)
  - [1. Install JDK 21](#1-install-jdk-21)
  - [2. Install ADB](#2-install-adb)
  - [3. Connect Device via WiFi ADB](#3-connect-device-via-wifi-adb)
  - [4. Create GitHub PAT](#4-create-github-pat)
  - [5. Download Tools](#5-download-tools)
  - [6. Set Up Patches Project](#6-set-up-patches-project)
  - [7. Verify Setup](#7-verify-setup)
- [How ReVanced Patching Works](#how-revanced-patching-works)
  - [Matching API](#matching-api)
- [Development Workflow](#development-workflow)
- [Troubleshooting](#troubleshooting)

---

## Overview

ReVanced is an open-source framework for patching Android apps. Patches are written
in **Kotlin** and applied to APK files using the ReVanced Patcher engine. The system
supports three patch types:

- **BytecodePatch** -- modifies Dalvik bytecode (the main type)
- **ResourcePatch** -- modifies decoded APK resources (XML, drawables)
- **RawResourcePatch** -- modifies arbitrary files in the APK

Patches locate target code in obfuscated apps using the **matching API**
(`composingFirstMethod`) that matches methods by access flags, return type, parameter
types, string constants, and opcode/instruction patterns -- making patches resilient
to obfuscation.

---

## Repositories

### Core (required for patch development)

| Repository | URL | Purpose |
|---|---|---|
| **revanced-patcher** | https://github.com/ReVanced/revanced-patcher | Patching engine/library (Kotlin). Consumed as a Gradle dependency. |
| **revanced-patches-template** | https://github.com/ReVanced/revanced-patches-template | Template for creating your own patches project. Clone this to start. |
| **revanced-patches-gradle-plugin** | https://github.com/ReVanced/revanced-patches-gradle-plugin | Gradle plugin providing the `patches {}` and `extension {}` DSL. |
| **revanced-cli** | https://github.com/ReVanced/revanced-cli | CLI tool (v6.0.0) for applying patches to APKs. Download the JAR. |

### Reference (useful, not required to clone)

| Repository | URL | Purpose |
|---|---|---|
| **revanced-patches** | https://github.com/ReVanced/revanced-patches (or GitLab mirror: https://gitlab.com/revanced/revanced-patches) | Official patches for YouTube, Instagram, etc. Read these to learn patch patterns. |
| **revanced-library** | https://github.com/ReVanced/revanced-library | Shared utilities used by CLI and Manager. |
| **revanced-manager** | https://github.com/ReVanced/revanced-manager | Android app for applying patches (alternative to CLI). |
| **GmsCore** | https://github.com/ReVanced/GmsCore | microG fork -- required for some patched Google apps on non-rooted devices. |

### Current versions (as of 2026-03)

- Patcher: **v22.0.1** (stable)
- CLI: **v6.0.0**
- Patches Gradle Plugin: **v1.0.0-dev.11**
- Manager: **v2.5.1**

> **Note:** The official revanced-patches repo was temporarily DMCA'd on GitHub
> (March 2026). Development continues on the GitLab mirror. The counter-notice has
> been filed and the repo is expected to be restored.

---

## External Requirements

### Software (WSL / Fedora)

| Tool | Version | Purpose | Install |
|---|---|---|---|
| **JDK 21** | 17+ (21 recommended) | Build patches (Kotlin/Gradle), run CLI | `sudo dnf install java-21-openjdk-devel` |
| **ADB** | any | Install APKs on device, pull APKs | `sudo dnf install android-tools` |
| **Git** | any | Clone repos | `sudo dnf install git` (likely already installed) |
| **curl / wget** | any | Download tool JARs | Pre-installed on Fedora |
| **unzip** | any | Extract JADX | `sudo dnf install unzip` |

### Tools (downloaded by `scripts/setup-tools.sh`)

| Tool | Purpose |
|---|---|
| **Android SDK** | Compiles extensions to DEX (cmdline-tools, platform, build-tools) |
| **JADX** | Decompile APK to readable Java source (AI-friendly output) |
| **APKTool** | Decode APK resources, disassemble to Smali |
| **ReVanced CLI** | Apply patches to APKs |
| **uber-apk-signer** | Sign patched APKs (required for installation) |
| **APKEditor** | Merge split APKs into a single universal APK for sharing |

### Hardware

- **Android device** with USB debugging enabled (no root necessary)
- Device and WSL machine on the **same WiFi network** (for wireless ADB)

### Online accounts

- **GitHub account** with a Personal Access Token (PAT) with `read:packages` scope
  (required for downloading ReVanced Maven dependencies from GitHub Packages)

---

## VSCode / Antigravity Extensions

### Recommended

| Extension | ID | Purpose |
|---|---|---|
| **APKLab** | `Surendrajat.apklab` | Android RE workbench -- integrates APKTool, JADX, signing, ADB install |
| **Kotlin** | `fwcd.kotlin` | Kotlin language server (completion, go-to-definition, diagnostics) |
| **Gradle for Java** | `vscjava.vscode-gradle` | Gradle build integration |
| **GitLens** | `eamodio.gitlens` | Track modifications in decompiled code |

### Optional

| Extension | ID | Purpose |
|---|---|---|
| **Hex Editor** | `ms-vscode.hexeditor` | Binary analysis of APK contents |
| **Smali** | `LoyieKing.smalise` | Smali syntax highlighting, go-to-definition |
| **XML** | `redhat.vscode-xml` | XML formatting, validation, schema support for Android resources |

---

## Setup Guide

### 1. Install JDK 21

```bash
sudo dnf install java-21-openjdk-devel
java -version   # verify: openjdk 21.x.x
```

If you have multiple JDK versions, set the default:

```bash
sudo alternatives --config java
# Select the java-21-openjdk option
```

Set `JAVA_HOME`:

```bash
echo 'export JAVA_HOME=/usr/lib/jvm/java-21-openjdk' >> ~/.bashrc
source ~/.bashrc
```

### 2. Install ADB

```bash
sudo dnf install android-tools
adb version   # verify
```

### 3. Connect Device via WiFi ADB

WiFi ADB is the recommended approach for WSL2 -- it avoids all USB passthrough
complexity (WSL2 cannot directly access USB devices).

#### On the Android phone:

1. Go to **Settings > About Phone**
2. Tap **Build Number** 7 times to enable Developer Options
3. Go to **Settings > System > Developer Options**
4. Enable **USB Debugging**
5. Enable **Wireless Debugging**
6. Tap **Wireless Debugging** to enter its sub-menu
7. Tap **Pair device with pairing code** -- note the IP:port and 6-digit code

#### In WSL:

```bash
# Pair (one-time per device)
adb pair <IP>:<PAIRING_PORT>
# Enter the 6-digit code when prompted

# Connect (needed after each phone reboot)
adb connect <IP>:<CONNECTION_PORT>
# The CONNECTION_PORT is shown on the Wireless Debugging main screen
# (different from the pairing port)

# Verify
adb devices
# Should show: <IP>:<PORT>    device
```

#### Convenience alias (add to ~/.bashrc):

```bash
alias adb-connect='adb connect <YOUR_DEVICE_IP>:<YOUR_CONNECTION_PORT>'
```

#### Alternative approaches (if WiFi doesn't work):

- **Windows ADB server**: Run `adb.exe` on Windows, set
  `ADB_SERVER_SOCKET=tcp:<windows-ip>:5037` in WSL. Requires USB connection to
  Windows and matching ADB versions.
- **usbipd-win**: USB passthrough to WSL2. Install with
  `winget install dorssel.usbipd-win`. More complex but works for USB-only scenarios.

### 4. Configure GitHub Packages authentication

ReVanced libraries are published to GitHub Packages, which requires authentication
even for public packages.

If you have the `gh` CLI configured, reuse your existing token:

```bash
gh auth token
# Copy the output
```

Otherwise, create a PAT at:
https://github.com/settings/tokens/new?scopes=read:packages&description=ReVanced

Add your credentials to `~/.gradle/gradle.properties` (user-level, outside the repo —
keeps secrets out of the project tree):

```properties
gpr.user=your-github-username
gpr.key=your-token-here
githubPackagesUsername=your-github-username
githubPackagesPassword=your-token-here
```

Both sets are needed: `gpr.*` resolves the Gradle plugin, `githubPackages*` is used
by the plugin internally to resolve patcher dependencies.

### 5. Download Tools

```bash
cd /home/andrewg/repos/revanced-patch-development
./scripts/setup-tools.sh
```

This downloads JADX, APKTool, ReVanced CLI, uber-apk-signer, APKEditor, and the
Android SDK (command-line tools, platform, build-tools) into `tools/`. It also syncs
Gradle dependency versions from `scripts/config.sh` into the patches project.

### 6. Set Up Patches Project

The patches project is already set up in the `patches/` directory (cloned from the
revanced-patches-template). To build:

```bash
cd patches
./gradlew build
```

This produces a `.rvp` (ReVanced Patches) file in `patches/patches/build/libs/`.

### 7. Verify Setup

```bash
# Check all tools
java -version
adb devices
./tools/jadx/bin/jadx --version
java -jar ./tools/revanced-cli.jar -h
java -jar ./tools/apktool.jar --version
```

---

## How ReVanced Patching Works

### Patch anatomy

A patch has two parts: **matches** (declared at file level) that locate target
methods in the obfuscated app, and a **patch function** that modifies the matched
methods.

```kotlin
package app.revanced.patches.instagram

import app.revanced.patcher.accessFlags
import app.revanced.patcher.composingFirstMethod
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.AccessFlags

// Step 1: Declare a match at file level.
// This locates the target method by structural properties (not names).
// String args are used for fast lookup (the patcher indexes string constants).
internal val BytecodePatchContext.myMatch by composingFirstMethod(
    "some_unique_string_in_the_method",
) {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("V")                                // return type
}

// Step 2: Declare the patch.
@Suppress("unused")
val myPatch = bytecodePatch(
    name = "My patch",
    description = "Does something useful.",
) {
    // Declare which app/versions this patch targets
    compatibleWith("com.instagram.android")

    // Optional: include companion code (compiled to DEX, merged into app)
    extendWith("extensions/extension.rve")

    // The patch logic — `this` is BytecodePatchContext
    apply {
        // Access the matched method via the delegate property.
        // The patcher scans all DEX classes to find the match at patch time.
        myMatch.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """,
        )
    }
}
```

### Required imports

Every patch file needs these core imports. Add others as needed:

```kotlin
// Matching.kt (match declarations)
import app.revanced.patcher.composingFirstMethod      // composingFirstMethod { } delegate
import app.revanced.patcher.accessFlags                // DSL: accessFlags(AccessFlags.PUBLIC, ...)
import app.revanced.patcher.returnType                 // DSL: returnType("V")
import app.revanced.patcher.parameterTypes             // DSL: parameterTypes("I", "L")
import app.revanced.patcher.opcodes                    // DSL: opcodes(Opcode.CONST, ...)
import app.revanced.patcher.strings                    // DSL: strings("extra_str")
import app.revanced.patcher.custom                     // DSL: custom { method -> ... }
import app.revanced.patcher.patch.BytecodePatchContext // receiver type for match properties
import com.android.tools.smali.dexlib2.AccessFlags     // AccessFlags.PUBLIC, etc.
import com.android.tools.smali.dexlib2.Opcode          // Opcode.INVOKE_VIRTUAL, etc.

// SomePatch.kt (patch logic)
import app.revanced.patcher.patch.bytecodePatch        // bytecodePatch { } DSL
import app.revanced.patcher.extensions.addInstructions  // MutableMethod.addInstructions()
```

> **Important:** The DSL methods (`accessFlags`, `returnType`, `parameterTypes`, `opcodes`,
> `strings`, `custom`) are extension functions on `MutablePredicateList<Method>`, exposed
> via context receivers. You must import each one you use from `app.revanced.patcher`.

### Matching API

The matching API finds methods in obfuscated code without relying on exact names.
Declare matches at file level (or in a `Matching.kt` file) using `composingFirstMethod`:

```kotlin
// Simple match — string constants are passed as args for fast lookup
internal val BytecodePatchContext.myMatch by composingFirstMethod(
    "unique_string",
) {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("V")
}

// Full match with all available predicates
// The lambda uses context receivers — `this` is MutablePredicateList<Method>,
// so DSL methods are called directly (no explicit lambda parameters).
internal val BytecodePatchContext.detailedMatch by composingFirstMethod(
    "string_for_lookup",           // vararg strings for index-based fast lookup
) {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)    // access modifier flags
    returnType("V")                 // return type descriptor (V=void, Z=boolean, etc.)
    parameterTypes("I", "Z")       // parameter type descriptors
    strings("extra_string")        // additional string constants in method body
    opcodes(Opcode.CONST_4)        // opcode sequence the method must contain
    name("methodName")             // exact method name (rarely used — obfuscated)
    definingClass("ClassName;")    // class name filter (rarely used — obfuscated)
    custom { method ->             // arbitrary predicate
        method.parameters.size > 2
    }
    instructions(                  // indexed instruction pattern matching
        string("some_text"),
        Opcode.INVOKE_VIRTUAL(),
        field { type == "I" },
    )
}
```

Inside the `apply { }` block (where `this` is `BytecodePatchContext`), access the
matched result via the delegate property:

| Property | Returns |
|---|---|
| `myMatch.method` | `MutableMethod` -- the matched method (throws if not found) |
| `myMatch.methodOrNull` | `MutableMethod?` -- null if no match |
| `myMatch.classDef` | `MutableClassDef` -- the class containing the match |
| `myMatch.classDefOrNull` | `MutableClassDef?` -- null if no match |
| `myMatch.indices` | `List<List<Int>>` -- matched instruction indices per matcher |
| `myMatch[0]` | `Int` -- first matched instruction index (for injection offset) |

#### Matching tiers

The patcher provides three matching functions, each progressively more powerful:

| Function | Returns | Use case |
|---|---|---|
| `firstMethod("str") { predicate }` | `MutableMethod` | Simple boolean predicate match |
| `firstMethodDeclaratively("str") { predicates -> }` | `MutableMethod` | Composable predicates with `anyOf`/`allOf`/`noneOf` |
| `firstMethodComposite("str") { /* DSL */ }` | `CompositeMatch` | Full DSL with instruction matching + indices |

Use `composingFirstMethod` (a delegate wrapper around `firstMethodComposite`) for
file-level declarations. All three have `OrNull` variants for graceful failure.

#### Instruction matching

`instructions { }` inside `composingFirstMethod` enables precise instruction-level
pattern matching with position constraints:

```kotlin
internal val BytecodePatchContext.preciseMatch by composingFirstMethod("lookup_str") {
    instructions(
        // Position constraints
        at(0, Opcode.CONST_STRING()),        // match at exact index
        after(Opcode.INVOKE_VIRTUAL()),      // anywhere after previous match
        after(1..3, string("text")),         // within 1-3 instructions after previous

        // Instruction matchers
        Opcode.CONST_4(),                    // match by opcode
        string("text"),                      // match string reference
        string("text", String::contains),    // match with predicate
        method { name == "x" },              // match method reference
        field { type == "I" },               // match field reference
        literal(42L),                        // match literal value
        "text"(),                            // shorthand string match
        42L(),                               // shorthand literal match

        // Combinators
        allOf(Opcode.INVOKE_VIRTUAL(), method { name == "x" }),  // AND
        anyOf(string("a"), string("b")),                          // OR
    )
}

// Access matched instruction indices for precise injection:
val insertIndex = preciseMatch[0]  // index of first matched instruction
preciseMatch.method.addInstructions(insertIndex, "...")
```

### Extensions

Extensions are companion Java/Kotlin code compiled to DEX and merged into the target
app at patch time. Use them when your patch needs runtime logic that's too complex for
inline Smali instructions:

```java
// In the extension (Java/Kotlin, compiled to DEX):
// File: extensions/extension/src/main/java/app/revanced/extension/AdBlocker.java
package app.revanced.extension;

public class AdBlocker {
    public static boolean shouldBlockAd() {
        return true;
    }
}
```

```kotlin
// In the patch (Kotlin):
myFingerprint.method.addInstructions(0, """
    invoke-static {}, Lapp/revanced/extension/AdBlocker;->shouldBlockAd()Z
    move-result v0
    if-eqz v0, :show_ad
    return-void
    :show_ad
""")
```

### Conventions and best practices

**File organization** (one directory per feature):
```
patches/src/main/kotlin/app/revanced/patches/instagram/
  ads/
    Matching.kt         # All matches for this feature
    HideAdsPatch.kt     # The patch itself
  feed/
    Matching.kt
    LimitFeedPatch.kt
```

**Matching tips:**
- String args passed to `composingFirstMethod("str")` use a pre-built string-to-method
  index for fast lookup — this is the most reliable and performant matcher
- Use `custom { method -> }` for arbitrary predicates
- Prefer few, high-confidence string matches over many opcode matches
- Use `instructions { }` when you need the matched instruction index for precise injection
- `OrNull` variants (`myMatch.methodOrNull`) allow graceful handling when a match
  isn't found (useful for patches targeting multiple app versions)
- The legacy `fingerprint { }` API still works but emits a deprecation warning

**Patch tips:**
- Use `apply { }` in the `bytecodePatch { }` block. The older `execute { }` name
  is deprecated.
- For simple method disabling, use `returnEarly()` / `returnEarly(false)` utilities
  from `app.revanced.util.BytecodeUtils` (available in the official patches repo).
- Interpolate field/method references directly into Smali strings:
  ```kotlin
  val field = fingerprint.classDef.fields.first { it.type == "Landroid/view/View;" }
  method.addInstructions(0, "iget-object v0, p0, $field")
  ```
- Use `addInstructionsWithLabels` + `ExternalLabel` for conditional branches:
  ```kotlin
  import app.revanced.patcher.extensions.addInstructionsWithLabels
  import app.revanced.patcher.extensions.getInstruction
  import app.revanced.patcher.util.smali.ExternalLabel

  method.addInstructionsWithLabels(
      0,
      """
          invoke-static {}, Lapp/revanced/extension/AdFilter;->shouldBlock()Z
          move-result v0
          if-nez v0, :allow
          return-void
      """,
      ExternalLabel("allow", method.getInstruction(0)),
  )
  ```

---

## Development Workflow

### 1. Add the app and get the APK

`add-app.sh` scaffolds the patches/extensions directories for a new app and
pulls or imports its APK in one step. Re-run it (with `--force`) to refresh
the APK for an existing app — the patches/extensions directories are
preserved. The `<app-name>` you choose here is used as the Kotlin package
segment (`app.revanced.patches.<app-name>`), so it must be lowercase
alphanumeric (underscores allowed).

```bash
# Option A — pull from device (if the app is installed)
./scripts/add-app.sh com.instagram.android instagram

# Option B — import a manually-downloaded file
# Supports .apk, .apkm (APKMirror), .xapk (APKPure), .apks.
# Bundle formats are auto-extracted into workspace/<app>/apk/ and split naming is
# normalized to split_*.apk so install.sh / package.sh pick them up unchanged.
# The primary APK is placed at workspace/<app>/apk/base.apk (identified via aapt2
# when the bundle doesn't already name it that). Package name is auto-detected
# from the imported APK so add-app.sh can still tell you which compatibleWith
# value to use.
./scripts/add-app.sh --file ~/Downloads/instagram.apkm instagram

# Option C — drop files manually into workspace/<app>/apk/, then re-run
# add-app.sh with --force to trigger scaffolding (or create the directories
# yourself under patches/.../patches/<app>/ and extensions/.../extension/<app>/)
```

After this runs, the app's patches directory exists at
`patches/patches/src/main/kotlin/app/revanced/patches/<app-name>/` with a
minimal `Matching.kt` (package declaration only). The same pattern applies to
any new app: pick a short lowercase name, run `add-app.sh`, then start
writing patches alongside Instagram's.

By default the script refuses to run if `workspace/<app>/apk/` already contains APK
files (to avoid mixing versions). Pass `--force` to overwrite.

> **Note on "universal" bundles**: a device pull only captures the splits installed
> on *that* device (ABI, density, language) and so isn't truly universal. APKMirror's
> complete bundle (.apkm) and APKPure's .xapk typically include all architectures
> and densities, which makes `--file` the better source for a portable patched APK.

### 2. Decompile

```bash
./scripts/decompile.sh workspace/instagram/apk/base.apk instagram
```

This creates:
- `workspace/instagram/decompiled/jadx/` -- readable Java source (for analysis)
- `workspace/instagram/decompiled/apktool/` -- resources + Smali (for reference)

### 3. Analyze (with AI assistance)

Open the decompiled code in your editor. Use Claude Code / Codex to search for
patterns, understand obfuscated code, and identify patch targets.

### 4. Write patches

Edit files in `patches/patches/src/main/kotlin/app/revanced/patches/<appname>/`.

### 5. Build and patch

```bash
./scripts/build.sh instagram
```

This builds your patches and applies them to the APK. Output includes the app version
in the filename: `output/instagram-<version>-patched.apk`

### 6. Install

```bash
./scripts/install.sh output/instagram-<version>-patched.apk
```

The install script automatically detects split APKs in `workspace/instagram/apk/`,
re-signs everything with the same key, and installs them together.

Use `--downgrade` to uninstall the existing app first (needed when signatures differ),
and `--launch` to open the app after installing.

### 7. Package for sharing (optional)

```bash
./scripts/package.sh instagram
```

Merges the patched base APK and all split APKs into a single universal APK
(`output/instagram-<version>-patched-universal.apk`) that can be shared and installed
directly without needing split APK installers.

---

## Troubleshooting

### Gradle build fails with 401 Unauthorized
Your GitHub PAT is missing or expired. Run `gh auth refresh -s read:packages`,
then update `~/.gradle/gradle.properties` with the new token from `gh auth token`.

### SDK location not found
Run `./scripts/setup-tools.sh` to install the Android SDK. The script sets
`ANDROID_HOME` automatically. If building outside the scripts, set it manually:
`export ANDROID_HOME=/path/to/revanced-patch-development/tools/android-sdk`

### `adb connect` fails
- Ensure phone and WSL are on the same WiFi network
- Re-enable Wireless Debugging on the phone (it can turn off after reboot)
- Check if the connection port changed (it changes on every phone reboot)

### Patch matching fails (fingerprint not found)
The target app version may have changed the method you're matching. Use JADX to
decompile the new version and update your matcher criteria.

### Patched app crashes on launch
- Check logcat: `adb logcat -s AndroidRuntime:E`
- Ensure your Smali instructions are valid (correct register count, types)
- If using extensions, verify the extension class path matches what the patch expects

### Kotlin metadata version mismatch
If the build reports "compiled with an incompatible version of Kotlin" (e.g. metadata
2.3.0 but compiler 2.1.0), the Kotlin compiler used for compilation is too old for
the patcher JAR. The `app.revanced.patches` Gradle plugin brings in the correct
Kotlin version (2.3.10 for plugin v1.0.0-dev.11). If you see this error:
- Ensure no other Kotlin plugin is applied that overrides the version.
- Run `./gradlew :patches:dependencies --configuration compileClasspath | grep kotlin`
  to verify the resolved Kotlin stdlib version matches the patcher's requirement.
- The VSCode Kotlin language server may report false metadata errors even when the
  Gradle build succeeds — trust the Gradle build output over IDE diagnostics.

### Instagram-specific notes
Instagram uses aggressive ProGuard/R8 obfuscation. Method/class names change between
versions. Always use `composingFirstMethod` with structural properties (`strings`,
`opcodes()`, `returnType()`, `accessFlags()`) rather than relying on class/method names.
