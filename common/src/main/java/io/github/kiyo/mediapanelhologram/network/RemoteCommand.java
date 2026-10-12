package io.github.kiyo.mediapanelhologram.network;

import org.jetbrains.annotations.Nullable;

/**
 * The fixed set of remote-control operations. Only the enum ordinal and one
 * int travel over the network; the JavaScript is built here from constants,
 * so a client can never make a server (or other clients) run arbitrary JS.
 */
public enum RemoteCommand {
    PLAY_TOGGLE, SEEK_BY, SET_RATE, TOGGLE_REPEAT, TOGGLE_CAPTIONS, PREV, NEXT, TOGGLE_SHUFFLE, TOGGLE_QUEUE,
    /** Handled by the server (persisted): arg = percent delta. */
    VOLUME_BY,
    /** Handled by the server (persisted). */
    TOGGLE_MUTE,
    /** Handled by the server: next QualityPreset. */
    NEXT_QUALITY;

    public static RemoteCommand fromOrdinal(int i) {
        RemoteCommand[] v = values();
        return (i >= 0 && i < v.length) ? v[i] : PLAY_TOGGLE;
    }

    /** window.mph call for the commands that are just forwarded to clients; null for server-handled ones. */
    @Nullable
    public String js(int arg) {
        return switch (this) {
            case PLAY_TOGGLE -> "toggle()";
            case SEEK_BY -> "seekBy(" + Math.max(-600, Math.min(600, arg)) + ")";
            case SET_RATE -> "setRate(" + Double.toString(Math.max(25, Math.min(400, arg)) / 100.0) + ")";
            case TOGGLE_REPEAT -> "toggleRepeat()";
            case TOGGLE_CAPTIONS -> "toggleCaptions()";
            case PREV -> "prev()";
            case NEXT -> "next()";
            case TOGGLE_SHUFFLE -> "toggleShuffle()";
            case TOGGLE_QUEUE -> "toggleQueue()";
            default -> null;
        };
    }
}
