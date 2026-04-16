package app.revanced.patches.instagram

import app.revanced.patcher.accessFlags
import app.revanced.patcher.composingFirstMethod
import app.revanced.patcher.custom
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * Matches the V1 SponsoredContentController insertItem method — the central
 * ad insertion point for feed, grid, story, and explore ads. Anchored on
 * the stable "SponsoredContentController.insertItem" systrace label.
 */
internal val BytecodePatchContext.adInjectorMatch by composingFirstMethod(
    "SponsoredContentController.insertItem",
) {
    accessFlags(AccessFlags.PRIVATE)
    returnType("Z")
    parameterTypes("L", "L")
}

/**
 * Matches the sponsored content fetch method used for feed, stories, explore,
 * and reels (the method branches internally by container module). Anchored
 * on a unique composite debug-log string emitted at method entry.
 */
internal val BytecodePatchContext.fetchSponsoredContentMatch by composingFirstMethod(
    "fetchSponsoredContent: organicContentIds.size=",
    ", isHeadLoad=",
    ", isPrefetch=",
) {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("V")
}

/**
 * Matches SponsoredContentControllerV2's static insertion helper. Instagram
 * switches from V1 to V2 after mobile-config sync, so both insertion points
 * can coexist and both need to be blocked.
 *
 * Matched structurally via invariants that survive Instagram's per-release
 * reobfuscation of identifier names:
 *  - public static final boolean with 3 object params (stable delegate
 *    signature: self, content item, insertion context).
 *  - Invokes java.lang.System.currentTimeMillis (stamps delivery time on
 *    the success branch).
 *  - Invokes java.util.Collections.singleton (wraps the content id on the
 *    reject-as-handled branch).
 * Java framework class names can't be obfuscated, so these invocations are
 * reliable anchors. An earlier version of this matcher anchored on an
 * ads-delivery mobile-config long literal, but that ID rotated between two
 * consecutive Instagram builds. Verified unique across both tested APKs:
 * exactly one public-static-final-Z method with 3 object params contains
 * both framework invocations, and the signature alone already discriminates
 * from the V1 helper above (which is private with 2 params).
 *
 * Implemented via custom { } rather than instructions(method { }, method { })
 * because the two invocations live in sibling branches of the same if/else,
 * and their relative emission order in bytecode is a D8/R8 basic-block
 * layout decision that can flip on a source refactor or compiler upgrade.
 * A custom predicate AND-combines two independent any-scans, which removes
 * any dependency on instruction order.
 */
internal val BytecodePatchContext.adInjectorV2Match by composingFirstMethod {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL)
    returnType("Z")
    parameterTypes("L", "L", "L")
    custom(fun(method: Method): Boolean {
        val instructions = method.implementation?.instructions ?: return false

        fun hasCall(definingClass: String, name: String) = instructions.any { insn: Instruction ->
            val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference
            ref?.definingClass == definingClass && ref.name == name
        }

        return hasCall("Ljava/lang/System;", "currentTimeMillis") &&
            hasCall("Ljava/util/Collections;", "singleton")
    })
}

/**
 * Matches the feed/explore ad ranking dispatch method. It builds and sends
 * the feed/async_ads_ranking/ request; blocking it prevents the ranking
 * network call. Anchored on the unique endpoint path string.
 */
internal val BytecodePatchContext.adRankingMatch by composingFirstMethod(
    "feed/async_ads_ranking/",
) {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("V")
    parameterTypes("L", "L")
}

/**
 * Matches the feed/explore ad prefetch trigger. Chosen because it is the
 * last safe choke point before the generic fetch state machine starts
 * (which mutates state and arms timers). Blocking it prevents the ad
 * content fetch request (feed/injected_reels_media/) from being built.
 *
 * Some subclasses override this method and take an alternate ODML fetch
 * path that bypasses super, so AlsoBlockAdFetchPatch additionally patches
 * any subclass overrides with the same signature.
 */
internal val BytecodePatchContext.adPrefetchMatch by composingFirstMethod(
    "pool_needs_refresh_early",
    "pool_needs_refresh_late",
) {
    returnType("V")
}

/**
 * Matches the story ad controller's toString method via its unique composite
 * strings. We don't patch toString — the patch uses its classDef to locate
 * the sibling controller/session init boolean method on the same class and
 * patches that instead.
 */
internal val BytecodePatchContext.storyAdControllerClassMatch by composingFirstMethod(
    ", numAdsInPool:",
    ", earliestRequestPosition:",
    ", currentIndex:",
) {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("Ljava/lang/String;")
    parameterTypes()
}

// Note: an ad-classifier patch was considered but dropped. The classifier
// method gates more than just ad labels — it also affects playback handling,
// ad session tracking, and media behavior for non-ad content. One caller
// has an independent code path that bypasses the classifier entirely, so
// patching it wouldn't even be complete. The risk of breaking non-ad media
// behavior outweighs the benefit as a safety net, given the V1/V2 insertion
// blocks and the fetch block already cover ads on all primary surfaces.

// -- Secondary ad fetch surfaces --
//
// Each match below anchors on a unique server endpoint path string and
// targets the request-building method that contains it. These are direct
// request builders that bypass the V1/V2 insertion hooks, the central
// fetchSponsoredContent path, and the ranking/prefetch/story-init blocks.
// Lower priority than the primary surfaces (mostly background-triggered
// or behind a feature gate) but worth covering to prevent leakage when
// Instagram flips a server flag.

/**
 * Reels alternate sponsored-content controller insertion sink. Instagram
 * has a feature-gated alternate clips controller whose fetch path bypasses
 * fetchSponsoredContent entirely; patching its request builder is unsafe
 * (caller dereferences the result inside a coroutine), so we block one
 * layer downstream instead — the void response handler that consumes the
 * fetched sponsored items and pushes them into the content collection.
 * Returning void at entry drops the response without touching the fetch
 * or the coroutine machinery.
 *
 * Anchored on two stable debug/analytics strings emitted in the handler:
 * the class's own error tag ("SimpleClipsSponsoredContentFetcher") and
 * the pagination response analytics event. Both are plain literal strings
 * — no obfuscated refs — and the tag uniquely identifies the class while
 * the event narrows to the response handler.
 */
internal val BytecodePatchContext.adAlternateClipsInsertionMatch by composingFirstMethod(
    "SimpleClipsSponsoredContentFetcher",
    "instagram_contextual_ads_pagination_response",
) {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("V")
    parameterTypes("L")
}

/**
 * Feed contextual multi-ad block. Background-triggered fetcher that
 * dispatches a contextual ad chain straight into the main feed without
 * going through the V1/V2 insertion hooks.
 */
internal val BytecodePatchContext.adContextualMultiMatch by composingFirstMethod(
    "feed/contextual_multi_ads/",
) {
    returnType("V")
}

/**
 * Feed AFI ("ads for interests") personalized ad fetcher. Builds and
 * dispatches a contextual ad request based on inferred user interests.
 */
internal val BytecodePatchContext.adInterestsContextualMatch by composingFirstMethod(
    "feed/user_interests_contextual_feed_of_ads/",
) {
    returnType("V")
}

/**
 * Story intent-aware ad fetcher. Fires when story viewing matches certain
 * "intent" heuristics and dispatches an additional ad request that bypasses
 * the legacy story controller path.
 */
internal val BytecodePatchContext.adStoryIntentAwareMatch by composingFirstMethod(
    "stories/stories_intent_aware_ads/",
) {
    returnType("V")
}

/**
 * Story high-intent ad discovery fetcher. Same family as the intent-aware
 * fetcher above — separate dispatch path, also independent of the main
 * story controller.
 */
internal val BytecodePatchContext.adStoryHighIntentMatch by composingFirstMethod(
    "stories/stories_high_intent_discovery_ads/",
) {
    returnType("V")
}

/**
 * Reels intent-aware ad fetcher. A standalone request builder wired into
 * the legacy reels sponsored-content controller's activation path — fires
 * during normal reels viewing, not via the central fetch path, so Patch 2
 * doesn't cover it. High-priority despite sitting in the direct-builder
 * section: reels is daily-use.
 */
internal val BytecodePatchContext.adReelsIntentAwareMatch by composingFirstMethod(
    "ads/intent_aware_ads/reels/",
) {
    returnType("V")
}

/**
 * Explore "injected chaining" ad fetcher. Publishes interstitial-tagged
 * ad units into the Explore feed. Triggered by the Explore controller
 * during normal browsing (not post-tap), so leaks into daily use.
 */
internal val BytecodePatchContext.adExploreChainingMatch by composingFirstMethod(
    "discover/injected_chaining_explore_media/",
) {
    returnType("V")
    parameterTypes("Ljava/util/List;", "I")
}

/**
 * Profile ad fetcher — scroll-position-gated variant. Fires when the
 * profile viewer scrolls past an index threshold configured by its
 * controller. One of two coexisting profile ad controllers; they share
 * the same endpoint but have distinct signatures (discriminated via
 * parameterTypes here and on the list variant below).
 */
internal val BytecodePatchContext.adProfileAdsSimpleMatch by composingFirstMethod(
    "profile_ads/get_profile_ads/",
) {
    returnType("V")
    parameterTypes("I")
}

/**
 * Profile ad fetcher — activation-triggered variant. Fires immediately
 * when the profile page's ad controller is activated (i.e., on profile
 * open), independent of scroll. Sibling to the simple variant above;
 * both need blocking because they run in parallel.
 */
internal val BytecodePatchContext.adProfileAdsListMatch by composingFirstMethod(
    "profile_ads/get_profile_ads/",
) {
    returnType("V")
    parameterTypes("Ljava/util/List;", "I")
}

/**
 * Comment-sheet ads fetcher on CommentSheetAdsNetworkFetcherKt — a
 * Kotlin suspend fun that builds, dispatches, and parses the comment-
 * sheet ads response. Fires during normal comment browsing once a
 * threshold counter exceeds ten (see HideAdsPatch for the trigger
 * chain). Returns Object (the coroutine result), not void, so it
 * can't be return-void'd at entry — the patch uses a discovery
 * pattern that picks the method's own "success, no ads" sentinel
 * out of its bytecode (see the injection block in HideAdsPatch).
 */
internal val BytecodePatchContext.adCommentSheetAdsMatch by composingFirstMethod(
    "ads/comment_sheet_ads/",
) {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL)
    returnType("Ljava/lang/Object;")
    parameterTypes(
        "Lcom/instagram/common/session/UserSession;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "L",
        "I",
    )
}

/**
 * Feed chaining contextual ads — seeded-ad dispatcher. Builds and sends
 * the seeded-ad shape of the chaining endpoint (carries seed_ad_id,
 * seed_ad_token, and chain tracking tokens). Fires from the public
 * dispatcher on the contextual-chain controller, reached via comment,
 * share, and profile taps on ordinary media when the controller's
 * seed-ad eligibility gate returns true. Seed values are derived from
 * the tapped media itself, so this variant is not restricted to post-
 * ad engagement.
 *
 * Anchored on the endpoint path plus "seed_ad_id". The endpoint string
 * appears in two sibling builders on this class; only the seeded-ad
 * builder also contains "seed_ad_id", while the organic-info sibling
 * uses "organic_info" instead.
 */
internal val BytecodePatchContext.adChainingSeededMatch by composingFirstMethod(
    "discover/chaining_experience_contextual_ads/",
    "seed_ad_id",
) {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("V")
}

/**
 * Feed chaining contextual ads — organic-info dispatcher. Public
 * dispatcher on the same contextual-chain controller that internally
 * builds the organic-info shape (carries organic_info of the tapped
 * media) and hands it to the dispatch helper. Fires on comment and
 * profile (including profile-story-ring) taps when the controller's
 * alternate eligibility gate returns true — the gate layers a
 * mobile-config random sample on top of media-eligibility checks.
 * Share taps short-circuit to the seeded-ad sibling and do not
 * reach this dispatcher. Response is bridged back into the main
 * feed by a delivery callback installed on the same controller, so
 * leaving the dispatcher unblocked lets ads land in the main feed
 * after ordinary interactions.
 *
 * Anchored on three analytics branch-tag strings that are emitted when
 * the method aborts early on different skip reasons. They are co-
 * located inside this dispatcher. Using returnType("V") filters out a
 * global string-pool class that also contains these literals as switch
 * cases but returns String.
 */
internal val BytecodePatchContext.adChainingOrganicMatch by composingFirstMethod(
    "num_iaa_reach_limit",
    "gap_zero_next_position_invalid",
    "gap_zero_next_item_is_sponsored",
) {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("V")
}
