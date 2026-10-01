package io.github.kiyo.mediapanelhologram.browser;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds a self-contained YouTube IFrame Player API page (as a data: URL) for a
 * video-mode URL. First attempt (plan "case 1"): data: pages have an opaque
 * origin, so YouTube may refuse playback (error 153 family); if so, move to a
 * custom scheme handler serving https://.../player.html (case 2).
 */
public final class VideoEmbed {
    private static final Pattern YOUTUBE_ID = Pattern.compile(
            "^(?:https?://)?(?:www\\.|m\\.)?(?:youtube\\.com/(?:watch\\?(?:.*&)?v=|shorts/|embed/|live/)|youtu\\.be/)"
                    + "([A-Za-z0-9_-]{11})(?:[&?#/].*)?$");

    private static final Map<String, String> CACHE = new HashMap<>();

    private static final String PAGE = """
            <!DOCTYPE html><html><head><meta charset="utf-8">
            <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}
            #player{position:absolute;inset:0;width:100%;height:100%}
            #msg{position:absolute;left:8px;top:8px;color:#f66;font:14px sans-serif;z-index:9}</style></head>
            <body><div id="msg"></div><div id="player"></div>
            <script src="https://www.youtube.com/iframe_api"></script>
            <script>
            var player;
            function onYouTubeIframeAPIReady(){
              player=new YT.Player('player',{width:'100%',height:'100%',videoId:'__ID__',
                playerVars:{autoplay:1,controls:1,rel:0,playsinline:1},
                events:{onError:function(e){document.getElementById('msg').textContent='YouTube player error '+e.data;console.log('YT error '+e.data);}}});
            }
            </script></body></html>
            """;

    private VideoEmbed() {
    }

    /** Returns the YouTube video id, or null if the URL is not a recognised YouTube video URL. */
    public static String youtubeId(String url) {
        if (url == null) {
            return null;
        }
        Matcher m = YOUTUBE_ID.matcher(url.trim());
        return m.matches() ? m.group(1) : null;
    }

    /** data: URL of the player page for this URL, or null if not YouTube. Cached per video id. */
    public static String toEmbedUrl(String url) {
        String id = youtubeId(url);
        if (id == null) {
            return null;
        }
        if (CACHE.size() > 64) {
            CACHE.clear();
        }
        return "https://mph.local/player?v=" + id;
    }
}
