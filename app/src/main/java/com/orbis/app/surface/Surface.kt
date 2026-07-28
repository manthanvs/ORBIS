package com.orbis.app.surface

/**
 * What the user is looking at right now, at a finer grain than "which app".
 *
 * [throttled] is the whole point: ORBIS slows short-form video feeds and leaves
 * everything else - Stories, DMs, chats, long-form video - untouched.
 */
enum class Surface(val throttled: Boolean) {
    /** Instagram Reels. */
    REELS(throttled = true),

    /** YouTube Shorts, in the YouTube app. */
    SHORTS(throttled = true),

    /** Snapchat Spotlight. */
    SPOTLIGHT(throttled = true),

    /** A short-video URL open in a browser, e.g. `m.youtube.com/shorts/<id>`. */
    BROWSER_SHORT_VIDEO(throttled = true),

    /**
     * Everything else, and the default whenever detection is unsure.
     *
     * Failing to NORMAL matters: an unrecognised screen must never be throttled,
     * because a false positive here slows something the user asked to keep fast.
     */
    NORMAL(throttled = false),
}
