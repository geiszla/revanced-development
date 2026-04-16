package app.revanced.patches.instagram

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * Blocks ad fetch and ranking network requests for feed, explore, and stories.
 * Optional companion to "Hide ads" — Hide ads is self-contained for not seeing
 * ads, while this patch is the network-saver layer that stops the upstream
 * requests from being built in the first place. Apply this on top of Hide ads
 * to spare bandwidth/battery/CPU; applying it alone won't hide ads that are
 * already in flight.
 *
 * Note: display-side coverage for secondary ad surfaces (alternate reels
 * controller, feed contextual ad chains, story intent-aware ad fetchers) all
 * lives in Hide ads as response-handler/request-builder blocks. This patch
 * focuses on the central ranking/prefetch/story-init pipeline above those.
 *
 * Patch 1 — Block ranking: aborts the feed/async_ads_ranking/ dispatch method
 *           at entry. This also skips some intermediate persistence writes
 *           and a "continue after ads store retrieve" flag, but the retry
 *           path re-enters this same (patched) method, so skipping them is
 *           safe.
 * Patch 2 — Block prefetch: aborts the feed/explore ad prefetch trigger at
 *           entry. This skips gating logic, pool-refresh bookkeeping, and
 *           the skip-reporting controller callback. The controller never
 *           receives a "checked, no fetch needed" signal, which may cause
 *           extra retry calls, but each retry also hits this return-void.
 *           Subclass overrides (which may take an alternate ODML fetch path
 *           bypassing super) are patched separately below.
 * Patch 3 — Block story ad controller init: returns false from the story-ad
 *           controller/session init boolean so the delegate declines to arm.
 *           The primary caller is null-safe when a delegate declines.
 */
@Suppress("unused")
val alsoBlockAdFetchPatch = bytecodePatch(
    name = "Also block ad fetch",
    description = "Extends Hide ads by also blocking ad ranking and prefetch network requests for feed, explore, and stories.",
) {
    compatibleWith("com.instagram.android")

    apply {
        // -- Patch 1: Block feed/explore ad ranking --
        //
        // Returning void at entry aborts the whole ranking path. This also
        // skips some intermediate persistence writes and a follow-up flag,
        // but those are only consumed by the retry path — which re-enters
        // this same (patched) method, so skipping them is safe.
        adRankingMatch.method.addInstructions(0, "return-void")

        // -- Patch 2: Block feed/explore ad prefetch --
        //
        // Returning void at entry skips gating, pool-refresh bookkeeping,
        // and the skip-reporting controller callback. The controller never
        // gets a "prefetch skipped" signal, which may cause extra retry
        // calls — but each one also returns void. The alternative of
        // injecting before the dispatch calls deeper in the method was
        // rejected: it requires fragile instruction-index targeting and
        // can't be applied uniformly to subclass overrides.
        adPrefetchMatch.method.addInstructions(0, "return-void")

        // Also patch subclass overrides of the prefetch method (some
        // subclasses take an alternate ODML fetch path that bypasses super).
        val prefetchClass = adPrefetchMatch.classDef
        val prefetchMethod = adPrefetchMatch.method

        classDefs.filter { it.superclass == prefetchClass.type }.forEach { subclass ->
            val override = subclass.methods.find {
                it.name == prefetchMethod.name &&
                    it.parameterTypes == prefetchMethod.parameterTypes
            } ?: return@forEach

            classDefs.getOrReplaceMutable(subclass).methods.first {
                it.name == override.name && it.parameterTypes == override.parameterTypes
            }.addInstructions(0, "return-void")
        }

        // -- Patch 3: Block story ad controller initialization --
        //
        // Returning false from the controller/session init boolean means
        // "decline to arm" — the primary caller is null-safe and skips the
        // delegate when it declines. A secondary IAA delegate still
        // forwards when present, but its meaningful registration path is
        // state-only, so declining here effectively suppresses story ad
        // fetch triggering.
        //
        // We can't match the init method directly (no unique strings), so
        // we match its sibling toString method (which has distinctive
        // strings) and pick the init method out of the same class by
        // signature + a structural anchor (contains invoke-super).
        val storyAdClass = storyAdControllerClassMatch.classDef

        val storyAdInitMethod = storyAdClass.methods.single { method ->
            method.accessFlags and (AccessFlags.PUBLIC.value or AccessFlags.FINAL.value) ==
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value &&
                method.returnType == "Z" &&
                method.parameterTypes.size == 3 &&
                method.parameterTypes.all { it.startsWith("L") } &&
                method.implementation?.instructions?.any {
                    it.opcode == Opcode.INVOKE_SUPER
                } == true
        }

        val mutableStoryAdInit = classDefs.getOrReplaceMutable(storyAdClass).methods.first {
            it.name == storyAdInitMethod.name &&
                it.parameterTypes == storyAdInitMethod.parameterTypes
        }

        mutableStoryAdInit.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """,
        )
    }
}
