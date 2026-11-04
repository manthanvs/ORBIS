package com.orbis.app.surface;

/**
 * What the user is looking at right now, at a finer grain than "which app".
 *
 * <p>Whether a surface is throttled is the whole point: ORBIS slows short-form
 * video feeds and leaves everything else - Stories, DMs, chats, long-form video
 * - untouched.
 */
public enum Surface {

    /** Instagram Reels. */
    REELS(true),

    /** YouTube Shorts, in the YouTube app. */
    SHORTS(true),

    /** Snapchat Spotlight. */
    SPOTLIGHT(true),

    /** A short-video URL open in a browser, e.g. {@code m.youtube.com/shorts/<id>}. */
    BROWSER_SHORT_VIDEO(true),

    /**
     * Everything else, and the default whenever detection is unsure.
     *
     * <p>Failing to NORMAL matters: an unrecognised screen must never be
     * throttled, because a false positive here slows something the user asked to
     * keep fast.
     */
    NORMAL(false);

    private final boolean throttled;

    Surface(boolean throttled) {
        this.throttled = throttled;
    }

    public boolean getThrottled() {
        return throttled;
    }
}
