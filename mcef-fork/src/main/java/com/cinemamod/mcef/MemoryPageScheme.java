package com.cinemamod.mcef;

import org.cef.callback.CefCallback;
import org.cef.handler.CefResourceHandler;
import org.cef.misc.IntRef;
import org.cef.misc.StringRef;
import org.cef.network.CefRequest;
import org.cef.network.CefResponse;

import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Serves the YouTube IFrame player page for https://mph.local/player?v=ID from memory,
 * so the page has a real https origin (a data: page gets YouTube error 153).
 * Local addition, not in upstream.
 */
public class MemoryPageScheme implements CefResourceHandler {
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
                playerVars:{autoplay:1,controls:1,rel:0,playsinline:1,origin:'https://mph.local'},
                events:{onError:function(e){document.getElementById('msg').textContent='YouTube player error '+e.data;}}});
            }
            </script></body></html>
            """;

    private final String requestUrl;
    private byte[] data;
    private int offset;

    public MemoryPageScheme(String requestUrl) {
        this.requestUrl = requestUrl;
    }

    @Override
    public boolean processRequest(CefRequest request, CefCallback callback) {
        String id = null;
        try {
            String q = URI.create(requestUrl).getRawQuery();
            if (q != null) {
                for (String part : q.split("&")) {
                    if (part.startsWith("v=")) {
                        id = part.substring(2);
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        if (id == null || !id.matches("[A-Za-z0-9_-]{11}")) {
            callback.cancel();
            return false;
        }
        data = PAGE.replace("__ID__", id).getBytes(StandardCharsets.UTF_8);
        callback.Continue();
        return true;
    }

    @Override
    public void getResponseHeaders(CefResponse response, IntRef responseLength, StringRef redirectUrl) {
        response.setMimeType("text/html");
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
