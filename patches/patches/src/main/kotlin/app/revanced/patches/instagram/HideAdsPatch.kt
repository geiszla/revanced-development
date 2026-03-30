package app.revanced.patches.instagram

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch

/**
 * Hides ads from Instagram's primary surfaces by cutting upstream insertion
 * and fetch paths.
 *
 * Patch 1 -- Feed/grid/story/explore (V1): blocks SponsoredContentController.insertItem
 *           (C98953oB.A0J), the V1 ad insertion point.
 * Patch 2 -- All surfaces: blocks the central fetchSponsoredContent method
 *           (C2DM.A0R) so no ads are fetched from the server. Despite the
 *           name, this single method handles feed, stories, explore, AND
 *           reels — it branches internally by container_module.
 * Patch 3 -- Feed/grid/story/explore (V2): blocks SponsoredContentControllerV2's
 *           insertion helper (C161926Ia.A02). Instagram switches to V2 after
 *           mobile config flag sync (36332197803747956L at C98353nD.java:672),
 *           which explains the "clean first run, ads after restart" pattern.
 *
 * Known uncovered surfaces (future work):
 *  - Related ads pivots (RelatedAdsPivotRepository — shop/commerce feeds)
 *  - Lead ads (LeadAdsActivity — interactive form overlays)
 *  - Direct inbox ad layouts
 *
 * Dropped patches (too risky / incomplete):
 *  - Stories ad pool (AFS.FJz): "stories_feed_of_ads" matches 6 methods,
 *    and Patches 1+3 already cover story ads at the insertion level.
 *  - Ad classifier (DqI): gates playback, session tracking, and media
 *    behavior beyond just labels; also incomplete (ReelItem.DqI has an
 *    independent A0z==A0Z path). Too global for a safety net.
 *
 * See workspace/instagram/patch-claude.md and patch-codex.md for analysis.
 */
@Suppress("unused")
val hideAdsPatch = bytecodePatch(
    name = "Hide ads",
    description = "Hides ads from feed, stories, reels, and explore.",
) {
    compatibleWith("com.instagram.android"("422.0.0.44.64"))
    extendWith("extensions/extension.rve")

    apply {
        // -- Patch 1: Block V1 feed/grid/story/explore ad insertion --
        //
        // SponsoredContentController.insertItem (C98953oB.A0J) is the V1
        // ad insertion method. Returning false at index 0 is safe because:
        //  - No state has been modified yet (Dvu adapter call at line 952 is
        //    also skipped, so the Dvu/Dvs notification pair stays balanced).
        //  - The profiler is not yet opened, so no resource leak.
        //  - All three callers handle false correctly:
        //      * Ejq (line 1305): ignores return, sets A0D=true (prevents retry)
        //      * Line 1340: skips E6v ad tracking notification (desired)
        //      * Line 1380: skips ad re-insertion/removal logic (desired)
        // This is the same early-return pattern used by official ReVanced patches.
        adInjectorMatch.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """,
        )

        // -- Patch 2: Block all sponsored content fetch --
        //
        // C2DM.A0R (fetchSponsoredContent) is a ~700-line method with queue
        // bookkeeping and state flags before the actual network dispatch.
        // Returning void at index 0 skips all of it, which means the fetch
        // state stays at "not attempted." The ad-fetch state machine handles
        // failures/timeouts already, so this is functionally equivalent to
        // "the fetch instantly completed with nothing." If this causes retry
        // churn in a future version, Patches 1+3 still block insertion.
        fetchSponsoredContentMatch.method.addInstructions(0, "return-void")

        // -- Patch 3: Block V2 feed/grid/story/explore ad insertion --
        //
        // SponsoredContentControllerV2 (C162066Io) delegates insertion to
        // the static helper C161926Ia.A02(). Same early-return-false pattern
        // as Patch 1 — returning false before Dvu() means no state is
        // modified and the Dvu/Dvs pair stays balanced.
        adInjectorV2Match.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """,
        )
    }
}
