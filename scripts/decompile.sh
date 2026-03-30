#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/config.sh"

# Decompile an APK into readable Java source (JADX) and resources (APKTool).
#
# Usage:
#   ./scripts/decompile.sh <apk-path> <app-name>
#   ./scripts/decompile.sh workspace/instagram/apk/base.apk instagram
#
# Options:
#   --jadx-only       Skip APKTool decompilation
#   --apktool-only    Skip JADX decompilation
#   --force           Overwrite existing decompiled output
#   --jadx-args "..." Extra arguments passed to JADX

APK_PATH=""
APP_NAME=""
DO_JADX=1
DO_APKTOOL=1
FORCE=0
EXTRA_JADX_ARGS=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --jadx-only)    DO_APKTOOL=0; shift ;;
        --apktool-only) DO_JADX=0; shift ;;
        --force)        FORCE=1; shift ;;
        --jadx-args)    EXTRA_JADX_ARGS="$2"; shift 2 ;;
        -*)             log_err "Unknown option: $1"; exit 1 ;;
        *)
            if [[ -z "$APK_PATH" ]]; then
                APK_PATH="$1"
            elif [[ -z "$APP_NAME" ]]; then
                APP_NAME="$1"
            else
                log_err "Unexpected argument: $1"
                exit 1
            fi
            shift ;;
    esac
done

if [[ -z "$APK_PATH" || -z "$APP_NAME" ]]; then
    echo "Usage: $0 <apk-path> <app-name> [--jadx-only] [--apktool-only] [--force]"
    exit 1
fi

if [[ ! -f "$APK_PATH" ]]; then
    log_err "APK not found: ${APK_PATH}"
    exit 1
fi

# Resolve absolute path
APK_PATH="$(realpath "$APK_PATH")"
DECOMPILE_DIR="${WORKSPACE_DIR}/${APP_NAME}/decompiled"
JADX_OUT="${DECOMPILE_DIR}/jadx"
APKTOOL_OUT="${DECOMPILE_DIR}/apktool"
JADX_BIN="${TOOLS_DIR}/jadx/bin/jadx"
APKTOOL_JAR="${TOOLS_DIR}/apktool.jar"

# ── JADX decompilation ──
if [[ $DO_JADX -eq 1 ]]; then
    if [[ ! -f "$JADX_BIN" ]]; then
        log_err "JADX not found. Run ./scripts/setup-tools.sh first."
        exit 1
    fi

    if [[ -d "$JADX_OUT" && $FORCE -eq 0 ]]; then
        log_warn "JADX output already exists: ${JADX_OUT}"
        log_warn "Use --force to overwrite, or delete it manually."
    else
        [[ -d "$JADX_OUT" ]] && rm -rf "$JADX_OUT"
        ensure_dir "$JADX_OUT"

        log_info "Decompiling with JADX (this may take a few minutes)..."
        log_info "  APK: ${APK_PATH}"
        log_info "  Output: ${JADX_OUT}"

        # Flags optimized for AI-readable output:
        #   --deobf                  Rename obfuscated identifiers
        #   --respect-bytecode-access-modifiers
        #                            Preserve original access flags (for accurate matchers)
        #   --show-bad-code          Output code even when decompilation partially fails
        #   --deobf-cfg-file-mode read-or-save
        #                            Reuse deobf mapping across re-decompilations
        #   --no-res                 Skip resources (APKTool handles those)
        #   --escape-unicode         Escape unicode chars for reliable string searching
        #   --threads-count          Parallelize for speed
        # JADX exits non-zero when it encounters decompilation errors (common
        # with obfuscated apps). This is expected -- --show-bad-code ensures
        # output is still produced.
        "${JADX_BIN}" \
            -d "${JADX_OUT}" \
            --deobf \
            --respect-bytecode-access-modifiers \
            --deobf-cfg-file-mode read-or-save \
            --no-res \
            --show-bad-code \
            --escape-unicode \
            --threads-count "$(nproc)" \
            ${EXTRA_JADX_ARGS} \
            "${APK_PATH}" || true

        log_ok "JADX decompilation complete: ${JADX_OUT}"
        log_info "  Java source: ${JADX_OUT}/sources/"
    fi
fi

# ── APKTool decompilation ──
if [[ $DO_APKTOOL -eq 1 ]]; then
    if [[ ! -f "$APKTOOL_JAR" ]]; then
        log_err "APKTool not found. Run ./scripts/setup-tools.sh first."
        exit 1
    fi

    if [[ -d "$APKTOOL_OUT" && $FORCE -eq 0 ]]; then
        log_warn "APKTool output already exists: ${APKTOOL_OUT}"
        log_warn "Use --force to overwrite, or delete it manually."
    else
        [[ -d "$APKTOOL_OUT" ]] && rm -rf "$APKTOOL_OUT"
        ensure_dir "$APKTOOL_OUT"

        log_info "Decoding with APKTool..."
        java -jar "${APKTOOL_JAR}" d \
            "${APK_PATH}" \
            -o "${APKTOOL_OUT}" \
            -f \
            -j "$(nproc)" \
            --keep-broken-res

        log_ok "APKTool decoding complete: ${APKTOOL_OUT}"
    fi
fi

echo ""
log_ok "Decompilation finished for ${APP_NAME}."
log_info "Browse decompiled code at: ${DECOMPILE_DIR}"
