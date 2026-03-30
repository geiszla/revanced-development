package app.revanced.patches.instagram

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * Blocks ad fetch and ranking network requests for feed, explore, and stories.
 * This is a companion to "Hide ads" — that patch blocks ad insertion (display),
 * while this patch blocks the upstream network calls so ads are never fetched
 * or ranked in the first place. Saves bandwidth and battery.
 *
 * Patch 1 -- Block ranking: aborts C122054kL.E6h at entry, preventing the
 *           feed/async_ads_ranking/ request. This also skips the A02/A03
 *           (module/session) persistence and the A05 "continue after ads
 *           store retrieve" flag, but the retry path (C117484cy:112)
 *           re-enters the same patched method, so skipping them is safe.
 * Patch 2 -- Block prefetch: aborts C122054kL.E5z at entry, preventing
 *           ad content fetch. This skips the method's gating logic, pool-
 *           refresh bookkeeping, and the skip-reporting path (A00→Dw5
 *           which updates controller state). The trade-off is accepted:
 *           the controller never receives a "checked, no fetch needed"
 *           signal, which may cause extra retry calls to E5z, but each
 *           retry also hits the return-void. Subclass overrides (e.g.
 *           XER's ODML path) are patched separately below.
 * Patch 3 -- Block story ad controller init: returns false from C5WP.A8k
 *           so the story ad delegate declines to arm. The caller (C5WQ)
 *           is null-safe when a delegate declines.
 */
@Suppress("unused")
val blockAdFetchPatch = bytecodePatch(
    name = "Block ad fetch",
    description = "Prevents ad ranking and prefetch network requests for feed, explore, and stories.",
) {
    compatibleWith("com.instagram.android"("422.0.0.44.64"))

    apply {
        // -- Patch 1: Block feed/explore ad ranking --
        //
        // C122054kL.E6h builds and dispatches feed/async_ads_ranking/.
        // Returning void at entry aborts the entire ranking path — this
        // also skips A02/A03 persistence and the A05 flag, but those are
        // only consumed by the retry path which re-enters this same
        // (patched) method.
        adRankingMatch.method.addInstructions(0, "return-void")

        // -- Patch 2: Block feed/explore ad prefetch --
        //
        // C122054kL.E5z is the prefetch trigger. Returning void at entry
        // skips gating, pool-refresh bookkeeping, and the skip-reporting
        // path (A00→Dw5 controller callback). This means the controller
        // never gets a "prefetch skipped" signal, which may cause extra
        // retry calls, but each one also returns void. The alternative
        // (injecting before the two dispatch calls A0H/A0G deeper in the
        // method) was rejected: it requires fragile instruction-index
        // targeting and can't be applied uniformly to subclass overrides.
        adPrefetchMatch.method.addInstructions(0, "return-void")

        // Also patch subclass overrides of E5z (e.g. XER's ODML scoring
        // path which bypasses super.E5z when c231558wZ.A03 is true).
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
        // C5WP.A8k is the controller/session init boolean for story ads.
        // Returning false means "decline to arm" — the caller (C5WQ.java:19)
        // is null-safe and skips the delegate. The secondary IAA delegate
        // (C5WV) still forwards to A00 when present, but the meaningful
        // registration path (C5WV.ESx) is state-only (C5WV.java:542), so
        // declining here effectively suppresses story ad fetch triggering.
        //
        // We find C5WP by matching its toString method (A0C) which has
        // distinctive strings, then select A8k from the same class by
        // signature + structural anchor (contains invoke-super to A8k).
        val storyAdClass = storyAdControllerClassMatch.classDef

        val a8kMethod = storyAdClass.methods.single { method ->
            method.accessFlags and (AccessFlags.PUBLIC.value or AccessFlags.FINAL.value) ==
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value &&
                method.returnType == "Z" &&
                method.parameterTypes.size == 3 &&
                method.parameterTypes.all { it.startsWith("L") } &&
                method.implementation?.instructions?.any {
                    it.opcode == Opcode.INVOKE_SUPER
                } == true
        }

        // Get the mutable version for patching
        val mutableA8k = classDefs.getOrReplaceMutable(storyAdClass).methods.first {
            it.name == a8kMethod.name && it.parameterTypes == a8kMethod.parameterTypes
        }

        mutableA8k.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """,
        )
    }
}
