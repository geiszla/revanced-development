package app.revanced.patches.instagram

import app.revanced.patcher.accessFlags
import app.revanced.patcher.composingFirstMethod
import app.revanced.patcher.instructions
import app.revanced.patcher.literal
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Matches SponsoredContentController.insertItem — the central ad insertion
 * point for feed, grid, story, and explore ads.
 * Decompiled ref: p000X/C98953oB.java:950
 */
internal val BytecodePatchContext.adInjectorMatch by composingFirstMethod(
    "SponsoredContentController.insertItem",
) {
    accessFlags(AccessFlags.PRIVATE)
    returnType("Z")
    parameterTypes("L", "L")
}

/**
 * Matches the reels/clips sponsored content fetch method.
 * Identified by the unique debug log string at the start of the method body.
 * Decompiled ref: p000X/C2DM.java:1324
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
 * Matches SponsoredContentControllerV2's insertion helper — the V2 equivalent
 * of insertItem. Instagram switches to V2 when mobile config flag
 * 36332197803747956L is enabled (C98353nD.java:672). On first launch the
 * flag isn't cached yet so V1 is used, but after config sync + restart the
 * app uses V2 and bypasses Patch 1.
 *
 * Matched structurally: public static final boolean with 3 object params,
 * containing the mobile config literal 36325798302995596L (shared with V1
 * but unique in combination with the static+3-param signature).
 * Decompiled ref: p000X/C161926Ia.java:123
 */
internal val BytecodePatchContext.adInjectorV2Match by composingFirstMethod {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL)
    returnType("Z")
    parameterTypes("L", "L", "L")
    instructions(
        literal(36325798302995596L),
    )
}

/**
 * Matches the feed/explore ad ranking dispatch method. This method builds
 * and sends the feed/async_ads_ranking/ request, which ranks ad candidates
 * for insertion. Blocking it prevents the ranking network call.
 * Decompiled ref: p000X/C122054kL.java:390
 */
internal val BytecodePatchContext.adRankingMatch by composingFirstMethod(
    "feed/async_ads_ranking/",
) {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("V")
    parameterTypes("L", "L")
}

/**
 * Matches the feed/explore ad prefetch trigger. This is the last safe
 * choke point before the generic fetch state machine starts (which mutates
 * state and starts timers). Blocking it prevents the ad content fetch
 * request (feed/injected_reels_media/) from being built.
 *
 * Note: XER subclass overrides E5z and takes its own ODML fetch path
 * when c231558wZ.A03 is true (bypasses super.E5z). BlockAdFetchPatch
 * handles this by also patching any subclass overrides of E5z.
 * Decompiled ref: p000X/C122054kL.java:276
 */
internal val BytecodePatchContext.adPrefetchMatch by composingFirstMethod(
    "pool_needs_refresh_early",
    "pool_needs_refresh_late",
) {
    returnType("V")
}

/**
 * Matches the story ad controller's toString (C5WP.A0C) via the unique
 * ", numAdsInPool:" string. We don't patch this method — we use its
 * classDef to find the sibling A8k() method (the controller/session
 * initialization boolean) and patch that instead.
 * Decompiled ref: p000X/C5WP.java:129
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

// Note: a DqI() ad classifier patch (AbstractC144325fA.A00) was considered
// but dropped. DqI() gates more than just ad labels — it affects playback
// handling (C1MG.java:55), ad session tracking (ViewOnKeyListenerC100373qT),
// and media behavior (C112254Mz). Additionally, ReelItem.DqI() has an
// independent `this.A0z == A0Z` code path that bypasses the underlying
// classifier entirely. The risk of breaking non-ad media behavior outweighs
// the benefit as a safety net, given Patches 1+2 already block ad insertion
// and fetching.
