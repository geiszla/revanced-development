package app.revanced.patches.instagram

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/**
 * Hides ads from Instagram's primary surfaces by cutting insertion and fetch
 * paths. Self-contained for the "don't show ads" use case — no companion
 * patch required. The "Also block ad fetch" patch is an optional network-
 * saver layer to apply on top when you also want to stop the upstream
 * requests.
 *
 * Primary surfaces (Patches 1-4):
 * Patch 1 — Feed/grid/story/explore (V1): blocks the V1 SponsoredContentController
 *           insertItem method at entry.
 * Patch 2 — Legacy reels + all other surfaces: blocks the central
 *           fetchSponsoredContent method so no ads are fetched from the
 *           server. Despite the name, this one method handles feed,
 *           stories, explore, AND reels via the legacy controller — it
 *           branches internally by container module.
 * Patch 3 — Feed/grid/story/explore (V2): blocks SponsoredContentControllerV2's
 *           insertion helper. Instagram switches to V2 after mobile-config
 *           sync, which explains the "clean first run, ads after restart"
 *           pattern on fresh installs.
 * Patch 4 — Alternate reels controller: when a server-flipped gate swaps
 *           the legacy reels controller for the alternate one, fetches
 *           bypass the central path in Patch 2 entirely. We can't null the
 *           request builder (its result is dereferenced unconditionally in
 *           a coroutine), so instead we no-op the response handler that
 *           inserts the fetched ads — the fetch completes normally, zero
 *           ads land on screen.
 *
 * Direct request-builder blocks: surfaces that ship their own request
 * builder rather than routing through the central fetch path. Each
 * anchors on unique in-binary strings (usually the endpoint path,
 * occasionally internal analytics branch tags) and uses methodOrNull so
 * a single brittle match can't abort the primary patches above. These
 * are all triggered by normal browsing (not post-tap or in-ad
 * interaction), so each one leaks into daily use if uncovered.
 *  - Feed contextual multi-ads (feed/contextual_multi_ads/)
 *  - Feed AFI personalized ads (feed/user_interests_contextual_feed_of_ads/)
 *  - Feed chaining contextual ads, two sibling public dispatchers on
 *    the same controller class (discover/chaining_experience_contextual_ads/).
 *    Fire on comment, share, and profile taps on ordinary media — not
 *    restricted to post-ad engagement — and the response is bridged back
 *    into the main feed via the controller's standard sponsored-delivery
 *    callback. Organic-info dispatcher anchors on internal branch-tag
 *    strings (no endpoint literal in its own body, only in the builder
 *    it calls); seeded-ad dispatcher anchors on the endpoint + seed_ad_id.
 *  - Explore injected chaining ads (discover/injected_chaining_explore_media/)
 *  - Reels intent-aware ads (ads/intent_aware_ads/reels/)
 *  - Story intent-aware ads (stories/stories_intent_aware_ads/)
 *  - Story high-intent discovery ads (stories/stories_high_intent_discovery_ads/)
 *  - Profile ads, two coexisting controllers (profile_ads/get_profile_ads/)
 *  - Comment sheet ads (ads/comment_sheet_ads/) — suspend-fun fetcher;
 *    patched via discovery of the method's own "success, no ads"
 *    sentinel rather than a plain return-void.
 *
 * Surfaces deliberately not blocked, grouped by reason:
 *
 * Post-engagement ad chains — fire only when the user has explicitly
 * engaged with an ad (tapping it, tapping an ad-linked notification,
 * entering a sponsored clips viewer). Deliberately not blocked: if the
 * user chose to tap into sponsored content, they've opted into that
 * experience and we shouldn't cut the follow-on content out from under
 * them. Revisit only if any of these starts firing on browse-only
 * triggers (i.e., without an upstream tap).
 *  - discover/chaining_experience_notification_ads/ — notification-tap
 *    ad chain; fires only when the user taps an Instagram notification
 *    that deep-links into sponsored content.
 *  - discover/feed_style_feed_of_ads/              — paginated "feed of
 *    ads" view reached only via tapping further into an existing chain.
 *  - ads/async_ads/                                — sponsored clips
 *    viewer chain request. Its builder is constructed only when the
 *    ClipsViewerSource matches one of the sponsored enum values
 *    (feed_of_ads, watch_and_browse_with_chaining, ad_chain), which
 *    are reached by tapping an ad or an ads-grid entry.
 *  - ads/async_ads/ads_only_lane/                  — ads-only lane
 *    variant of the same sponsored clips viewer chain.
 *  - clips/ads_discover_sync_flow/ at the builder level — alternate
 *    clips request builder for the sponsored clips viewer family.
 *    Not patched at the builder level because the result is
 *    dereferenced unconditionally inside a coroutine (NPE if nulled);
 *    for the browse-triggered variant where the alternate reels
 *    controller consumes this endpoint's response, Patch 4 above
 *    covers the insertion side.
 *  - Related ads pivots (shop/commerce feeds) — contextual product
 *    ads shown inside the Shop tab or after the user taps into a
 *    product. The entire surface is opt-in commerce browsing.
 *  - Lead ads (interactive form overlays) — the form sheet opens
 *    only after the user taps a CTA button on an existing ad.
 *  - Direct CTD (click-to-direct) thread banners — when a user taps
 *    an ad's "Send Message" CTA, Instagram opens a DM thread with
 *    the advertiser and shows an "about this ad" banner at the top.
 *    Gated in-code on entryPoint == "message_button_ctd" plus a
 *    bundle_extra_is_navigating_from_ctd_ad flag carried into the
 *    thread, so this only surfaces as a consequence of an explicit
 *    CTD tap. Business-account inbox surfaces (campaign list, ad
 *    responses tab, CTD messaging upsell) are out of scope — those
 *    are advertiser management UI, not recipient-side ad injections.
 *
 * Downstream follow-on fetches — fire only when a sponsored parent item
 * is already visible and the user engages further with it (expands a
 * carousel, swipes through cards). The primary blocks prevent parent
 * ads from being delivered in the first place, so in practice these
 * never run; if they ever do, the parent ad is already on screen and
 * blocking these wouldn't reduce what the user sees.
 *  - ads/async_get_ondemand_carousel_cards/         — feed/clips
 *    carousel lazy-loads additional cards for an already-visible
 *    sponsored carousel ad. Gated on a non-null ad_client_token from
 *    the parent media.
 *  - ads/async_get_ondemand_carousel_cards_stories/ — story carousel
 *    equivalent; takes an existing ReelItem and fetches more cards
 *    for it.
 *
 * Considered but dropped (too risky / incomplete):
 *  - Stories ad pool gate: its anchor string matches too many methods, and
 *    Patches 1+3 already cover story ads at the insertion level.
 *  - Ad classifier: gates playback, session tracking, and media behavior
 *    beyond just ad labels; one caller also has an independent bypass path.
 *    Too global for a safety net.
 */
@Suppress("unused")
val hideAdsPatch = bytecodePatch(
    name = "Hide ads",
    description = "Hides ads from feed, stories, reels, and explore.",
) {
    compatibleWith("com.instagram.android")
    extendWith("extensions/extension.rve")

    apply {
        // -- Patch 1: Block V1 feed/grid/story/explore ad insertion --
        //
        // Returning false from insertItem at index 0 is safe because:
        //  - No state has been modified yet; the adapter-notify pair stays
        //    balanced (both sides of it are skipped).
        //  - The systrace profiler section is not yet opened, so nothing leaks.
        //  - All callers handle a false return correctly: they either ignore
        //    it entirely or use it to skip downstream tracking/re-insertion.
        // Same early-return pattern used by official ReVanced patches.
        adInjectorMatch.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """,
        )

        // -- Patch 2: Block all sponsored content fetch --
        //
        // fetchSponsoredContent has hundreds of lines of queue bookkeeping
        // and state flags before the network dispatch. Returning void at
        // entry skips all of it, leaving the fetch state at "not attempted."
        // The ad-fetch state machine already handles failures/timeouts, so
        // this is functionally equivalent to "the fetch completed with
        // nothing." If this ever causes retry churn, Patches 1+3 still block
        // the display side.
        fetchSponsoredContentMatch.method.addInstructions(0, "return-void")

        // -- Patch 3: Block V2 feed/grid/story/explore ad insertion --
        //
        // SponsoredContentControllerV2 delegates insertion to a static
        // helper. Same early-return-false pattern as Patch 1. The match
        // anchors on Java framework calls rather than a mobile-config
        // literal so it survives Instagram's per-release reobfuscation of
        // identifier names (see Matching.kt).
        adInjectorV2Match.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """,
        )

        // -- Patch 4: Block alternate reels controller ad insertion --
        //
        // When the server gate selects the alternate clips controller, its
        // fetch path bypasses Patch 2. The request builder can't be safely
        // nulled (the result is dereferenced unconditionally inside a
        // coroutine), so we no-op the response handler instead — the fetch
        // succeeds, the coroutine completes, and the response is silently
        // discarded before any ad is inserted. Side effects skipped by the
        // return-void (analytics logging, prefetch of ad media, pagination
        // counter increment) are all ad-specific; downstream retries would
        // hit this same block.
        adAlternateClipsInsertionMatch.method.addInstructions(0, "return-void")

        // -- Direct request-builder blocks --
        //
        // Endpoint-string-anchored blocks for surfaces that ship their own
        // request builder instead of routing through the central fetch.
        // All trigger on normal browsing (scroll, activation, background
        // interest scoring), not on ad taps. Each uses methodOrNull so a
        // single brittle match can't abort the primary patches above: if
        // Instagram renames or refactors one of these narrower request
        // builders, the build still succeeds and the remaining blocks
        // still apply. Organized by surface: feed → explore → reels →
        // story → profile.

        // Feed: contextual multi-ad chain (background interest scoring
        // dispatches a contextual ad block straight into the main feed).
        adContextualMultiMatch.methodOrNull?.addInstructions(0, "return-void")

        // Feed: AFI ("ads for interests") personalized ad fetcher.
        adInterestsContextualMatch.methodOrNull?.addInstructions(0, "return-void")

        // Feed: chaining contextual ads. Two sibling dispatchers on the
        // same controller — seeded-ad routes comment, share, and profile
        // taps; organic-info routes only comment and profile taps (share
        // short-circuits to the seeded path).
        adChainingOrganicMatch.methodOrNull?.addInstructions(0, "return-void")
        adChainingSeededMatch.methodOrNull?.addInstructions(0, "return-void")

        // Explore: "injected chaining" ad fetcher. Publishes interstitial-
        // tagged units into the Explore feed during normal browsing.
        adExploreChainingMatch.methodOrNull?.addInstructions(0, "return-void")

        // Reels: intent-aware ad fetcher. Wired into the legacy reels
        // controller's activation path — Patch 2 doesn't cover it because
        // it's a separate request builder, not the central fetch. High
        // priority for reels coverage.
        adReelsIntentAwareMatch.methodOrNull?.addInstructions(0, "return-void")

        // Story: intent-aware ad fetcher (alternate story ad path that
        // dispatches outside the legacy controller lifecycle).
        adStoryIntentAwareMatch.methodOrNull?.addInstructions(0, "return-void")

        // Story: high-intent ad discovery fetcher (sibling of the intent-
        // aware fetcher, also bypasses the legacy controller).
        adStoryHighIntentMatch.methodOrNull?.addInstructions(0, "return-void")

        // Profile: two coexisting ad controllers share the same endpoint
        // but have distinct signatures. The simple variant fires when the
        // viewer scrolls past an index threshold; the list variant fires
        // immediately on controller activation (profile open). Both need
        // blocking because they run in parallel.
        adProfileAdsSimpleMatch.methodOrNull?.addInstructions(0, "return-void")
        adProfileAdsListMatch.methodOrNull?.addInstructions(0, "return-void")

        // Comment sheet: ads/comment_sheet_ads/ — the fetcher is a Kotlin
        // suspend fun that returns Object, so return-void at entry would
        // be invalid bytecode. Instead we leverage Instagram's own empty-
        // result sentinel: the method already contains an sget-object +
        // return-object pair loading a singleton field whose type equals
        // its defining class (the idiomatic "singleton with an A00 static
        // field" pattern used for Kotlin objects). The consumer normalizes
        // both the success-empty sentinel and the error sentinel into the
        // same no-ops callback, so either works semantically — we pick
        // the first one we find, which on current builds lands on the
        // success-empty sentinel because the compiler emits main-flow
        // branches before out-of-line error handlers. If a future
        // reordering flips the order, we'd pick the error sentinel
        // instead, which the consumer still treats identically. Copying
        // Instagram's own reference out of the matched method's bytecode
        // keeps the injection in sync with each release's obfuscation —
        // no hardcoded X.* names needed.
        adCommentSheetAdsMatch.methodOrNull?.let { method ->
            val instructions = method.implementation?.instructions?.toList()
                ?: return@let

            var sentinelLoad: String? = null

            for (i in 0 until instructions.size - 1) {
                val instruction = instructions[i]

                if (instruction.opcode != Opcode.SGET_OBJECT) continue
                if (instructions[i + 1].opcode != Opcode.RETURN_OBJECT) continue

                val ref = (instruction as? ReferenceInstruction)?.reference as? FieldReference
                    ?: continue

                if (ref.type != ref.definingClass) continue

                sentinelLoad = "sget-object v0, ${ref.definingClass}->${ref.name}:${ref.type}"

                break
            }

            sentinelLoad?.let { load ->
                method.addInstructions(
                    0,
                    """
                        $load
                        return-object v0
                    """,
                )
            }
        }
    }
}
