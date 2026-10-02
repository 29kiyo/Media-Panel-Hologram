package com.cinemamod.mcef;

import org.cef.callback.CefCallback;
import org.cef.handler.CefResourceHandler;
import org.cef.misc.IntRef;
import org.cef.misc.StringRef;
import org.cef.network.CefRequest;
import org.cef.network.CefResponse;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Serves in-memory pages under https://mph.local/ (a data: page gets YouTube error 153).
 * Routes: /player?v=ID (YouTube IFrame player), /file?u=ENCODED_URL (plain <video> for direct files).
 * Every page exposes window.mph = {play,pause,toggle,setVolume,setMuted,setRate,seek,seekBy}
 * for remote control via executeJavaScript. Local addition, not in upstream.
 */
public class MemoryPageScheme implements CefResourceHandler {
    private static final String YOUTUBE_PAGE = """
            <!DOCTYPE html><html><head><meta charset="utf-8">
            <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}
            #player{position:absolute;inset:0;width:100%;height:100%}
            #msg{position:absolute;left:8px;top:8px;color:#f66;font:14px sans-serif;z-index:9}</style></head>
            <body><div id="msg"></div><div id="player"></div>
            <script src="https://www.youtube.com/iframe_api"></script>
            <script>
            var player;
            function ytErr(c){
              if(c===101||c===150||c===153){location.replace('https://www.youtube.com/watch?v=__ID__');return;}
              document.getElementById('msg').textContent='YouTube player error '+c;
            }
            function ok(){return player&&player.getPlayerState;}
            window.mph={
              play:function(){if(ok())player.playVideo();},
              pause:function(){if(ok())player.pauseVideo();},
              toggle:function(){if(ok()){if(player.getPlayerState()===1){player.pauseVideo();}else{player.playVideo();}}},
              setVolume:function(x){if(ok())player.setVolume(Math.round(Math.max(0,Math.min(1,x))*100));},
              setMuted:function(m){if(ok()){if(m){player.mute();}else{player.unMute();}}},
              volumeBy:function(d){if(ok())player.setVolume(Math.max(0,Math.min(100,player.getVolume()+d*100)));},
              toggleMute:function(){if(ok()){if(player.isMuted()){player.unMute();}else{player.mute();}}},
              setRate:function(r){if(ok())player.setPlaybackRate(r);},
              seek:function(s){if(ok())player.seekTo(s,true);},
              seekBy:function(d){if(ok())player.seekTo(Math.max(0,player.getCurrentTime()+d),true);}
            };
            function onYouTubeIframeAPIReady(){
              player=new YT.Player('player',{width:'100%',height:'100%',videoId:'__ID__',
                playerVars:{autoplay:1,controls:1,rel:0,playsinline:1,origin:'https://mph.local'},
                events:{onError:function(e){ytErr(e.data);}}});
              if(__FE__>0){setTimeout(function(){ytErr(__FE__);},1500);}
            }
            </script></body></html>
            """;

    private static final String FILE_PAGE = """
            <!DOCTYPE html><html><head><meta charset="utf-8">
            <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}
            video{position:absolute;inset:0;width:100%;height:100%;object-fit:contain}
            #msg{position:absolute;left:8px;top:8px;color:#f66;font:14px sans-serif;z-index:9}</style></head>
            <body><div id="msg"></div><video id="v" data-src="__SRC__" autoplay playsinline controls></video>
            <script src="/hls.min.js"></script>
            <script>
            var v=document.getElementById('v');
            var src=v.getAttribute('data-src');
            if(src.toLowerCase().indexOf('.m3u8')>=0 && window.Hls && Hls.isSupported()){
              var hls=new Hls(); hls.loadSource(src); hls.attachMedia(v);
              hls.on(Hls.Events.ERROR,function(e,d){if(d.fatal){document.getElementById('msg').textContent='HLS error '+d.type+' '+d.details+' code='+(d.response?d.response.code:'none')+' url='+(d.url||src);}});
            } else { v.src=src; }
            v.addEventListener('error',function(){var e=v.error;
              document.getElementById('msg').textContent='Video error '+(e?e.code:'')+(e&&e.message?' '+e.message:'');});
            window.mph={
              play:function(){v.play();}, pause:function(){v.pause();},
              toggle:function(){if(v.paused){v.play();}else{v.pause();}},
              setVolume:function(x){v.volume=Math.max(0,Math.min(1,x));},
              setMuted:function(m){v.muted=!!m;}, setRate:function(r){v.playbackRate=r;},
              volumeBy:function(d){v.volume=Math.max(0,Math.min(1,v.volume+d));},
              toggleMute:function(){v.muted=!v.muted;},
              seek:function(s){v.currentTime=s;}, seekBy:function(d){v.currentTime=Math.max(0,v.currentTime+d);}
            };
            </script></body></html>
            """;

    private final String requestUrl;
    private byte[] data;
    private String mime = "text/html";
    private int offset;

    public MemoryPageScheme(String requestUrl) {
        this.requestUrl = requestUrl;
    }

    private static String param(String rawQuery, String key) {
        if (rawQuery == null) {
            return null;
        }
        for (String part : rawQuery.split("&")) {
            if (part.startsWith(key + "=")) {
                return URLDecoder.decode(part.substring(key.length() + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String escapeAttr(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Override
    public boolean processRequest(CefRequest request, CefCallback callback) {
        String html = null;
        try {
            URI uri = URI.create(requestUrl);
            if ("/hls.min.js".equals(uri.getPath())) {
                try (java.io.InputStream in = MemoryPageScheme.class.getResourceAsStream("/mph/hls.min.js")) {
                    if (in != null) {
                        data = in.readAllBytes();
                        mime = "application/javascript";
                        callback.Continue();
                        return true;
                    }
                } catch (java.io.IOException ignored) {
                }
                callback.cancel();
                return false;
            }
            String query = uri.getRawQuery();
            if ("/file".equals(uri.getPath())) {
                String src = param(query, "u");
                if (src != null && src.matches("(?i)^https?://\\S+$")) {
                    html = FILE_PAGE.replace("__SRC__", escapeAttr(src));
                }
            } else {
                String id = param(query, "v");
                if (id != null && id.matches("[A-Za-z0-9_-]{11}")) {
                    String fe = param(query, "fe");
                    html = YOUTUBE_PAGE.replace("__ID__", id)
                            .replace("__FE__", fe != null && fe.matches("\\d{1,3}") ? fe : "0");
                }
            }
        } catch (RuntimeException ignored) {
        }
        if (html == null) {
            callback.cancel();
            return false;
        }
        data = html.getBytes(StandardCharsets.UTF_8);
        callback.Continue();
        return true;
    }

    @Override
    public void getResponseHeaders(CefResponse response, IntRef responseLength, StringRef redirectUrl) {
        response.setMimeType(mime);
        response.setStatus(200);
        response.setStatusText("OK");
        responseLength.set(data != null ? data.length : 0);
    }

    @Override
    public boolean readResponse(byte[] dataOut, int bytesToRead, IntRef bytesRead, CefCallback callback) {
        if (data == null || offset >= data.length) {
            bytesRead.set(0);
            return false;
        }
        int n = Math.min(bytesToRead, data.length - offset);
        System.arraycopy(data, offset, dataOut, 0, n);
        offset += n;
        bytesRead.set(n);
        return true;
    }

    @Override
    public void cancel() {
    }
}
