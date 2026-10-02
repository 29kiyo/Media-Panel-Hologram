package io.github.kiyo.mediapanelhologram.browser;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps a video-mode URL to an in-memory player page served by mcef-fork's
 * MemoryPageScheme (https://mph.local/...). Returns null for unrecognised URLs,
 * which are then opened as ordinary pages.
 */
public final class VideoEmbed {
    private static final Pattern YOUTUBE_ID = Pattern.compile(
            "^(?:https?://)?(?:www\\.|m\\.)?(?:youtube\\.com/(?:watch\\?(?:.*&)?v=|shorts/|embed/|live/)|youtu\\.be/)"
                    + "([A-Za-z0-9_-]{11})(?:[&?#/].*)?$");
    private static final Pattern DIRECT_FILE = Pattern.compile(
            "^https?://\\S+\\.(?:mp4|webm|ogv|m4v|m3u8)(?:[?#]\\S*)?$", Pattern.CASE_INSENSITIVE);

    private VideoEmbed() {
    }

    /** Image-mode page URL (scaled to fill the screen, falls back to the plain page), or null if not http(s). */
    public static String toImageEmbedUrl(String url) {
        if (url == null) {
            return null;
        }
        String trimmed = url.trim();
        if (trimmed.startsWith("https://mph.local/") || !trimmed.matches("(?i)^https?://\\S+$")) {
            return null;
        }
        return "https://mph.local/image?u=" + URLEncoder.encode(trimmed, StandardCharsets.UTF_8);
    }

    /** Returns the YouTube video id, or null if the URL is not a recognised YouTube video URL. */
    public static String youtubeId(String url) {
        if (url == null) {
            return null;
        }
        Matcher m = YOUTUBE_ID.matcher(url.trim());
        return m.matches() ? m.group(1) : null;
    }

    /** Player page URL for this video URL, or null if no embed applies. */
    public static String toEmbedUrl(String url) {
        if (url == null || url.trim().startsWith("https://mph.local/")) {
            return null;
        }
        String id = youtubeId(url);
        if (id != null) {
            return "https://mph.local/player?v=" + id;
        }
        String trimmed = url.trim();
        if (DIRECT_FILE.matcher(trimmed).matches()) {
            return "https://mph.local/file?u=" + URLEncoder.encode(trimmed, StandardCharsets.UTF_8);
        }
        return null;
    }
}
