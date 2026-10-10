package com.shiva.personalai;

/** Tracks whether any of our screens is visible; Android only allows background mic use if it was started then. */
final class Foreground {
    private static int resumed;

    private Foreground() { }

    static synchronized void resumed() { resumed++; }

    static synchronized void paused() { resumed = Math.max(0, resumed - 1); }

    static synchronized boolean visible() { return resumed > 0; }
}
