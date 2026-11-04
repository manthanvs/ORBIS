package com.orbis.app.usage;

public enum UsageEventType {
    /** The app became visible to the user (ACTIVITY_RESUMED). */
    FOREGROUND,

    /** The app stopped being visible (ACTIVITY_PAUSED). */
    BACKGROUND
}
