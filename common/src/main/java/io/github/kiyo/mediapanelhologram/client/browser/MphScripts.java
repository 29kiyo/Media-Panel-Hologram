package io.github.kiyo.mediapanelhologram.client.browser;

/**
 * JavaScript snippets shared by every caller that drives window.mph
 * (MediaBrowserScreen buttons, remote-control commands). Pure strings, no
 * Minecraft/MCEF types, so it is safe to reference from any client code.
 */
public final class MphScripts {
    /**
     * Fallback window.mph for ordinary pages (best effort): operates the largest <video> in the main
     * frame. Does nothing if there is no video, it sits in a cross-origin iframe, or the site uses
     * DRM. Pages under https://mph.local/ already define their own window.mph, so this is skipped.
     */
    public static final String GENERIC_MPH = """
            if(!window.mph){(function(){
            function vid(){var best=null,area=-1;
              Array.prototype.slice.call(document.querySelectorAll('video')).forEach(function(v){
                var r=v.getBoundingClientRect();var a=r.width*r.height;if(a>area){area=a;best=v;}});
              if(best){return best;}
              var au=Array.prototype.slice.call(document.querySelectorAll('audio'));
              for(var i=0;i<au.length;i++){if(!au[i].paused){return au[i];}}
              return au.length?au[0]:null;}
            function w(f){return function(x){var v=vid();if(v){f(v,x);}};}
            window.mph={
              play:w(function(v){v.play();}),
              pause:w(function(v){v.pause();}),
              toggle:w(function(v){if(v.paused){v.play();}else{v.pause();}}),
              setVolume:w(function(v,x){v.volume=Math.max(0,Math.min(1,x));}),
              setMuted:w(function(v,x){v.muted=!!x;}),
              setRate:w(function(v,x){
                window.__mphRate=x;v.playbackRate=x;
                if(!v.__mphHook){v.__mphHook=true;
                  v.addEventListener('ratechange',function(){
                    var r=window.__mphRate;
                    if(r&&r!==1&&v.playbackRate!==r){v.playbackRate=r;}
                  });}
              }),
              seek:w(function(v,x){v.currentTime=x;}),
              seekBy:w(function(v,x){v.currentTime=Math.max(0,v.currentTime+x);}),
              volumeBy:w(function(v,x){v.volume=Math.max(0,Math.min(1,v.volume+x));}),
              toggleMute:w(function(v){if(!v.muted){window.__mphVol=v.volume;v.muted=true;}else{v.muted=false;if(v.volume===0){v.volume=(window.__mphVol>0)?window.__mphVol:0.5;}}}),
              toggleRepeat:w(function(v){v.loop=!v.loop;}),
              state:function(){var v=vid();return v?{p:v.currentTime,pl:!v.paused&&!v.ended,r:v.playbackRate,d:isFinite(v.duration)?v.duration:-1}:null;},
              next:function(){var b=document.querySelector('.ytp-next-button');if(b){b.click();}}
            };})();}
            """;

    /** Full script for one call such as "seekBy(10)": define the generic fallback, then call it, never throwing. */
    public static String script(String call) {
        return GENERIC_MPH + "try{window.mph." + call + ";}catch(e){}";
    }

    /**
     * Applies a persisted volume/mute state. volume is 0..1, or negative = do not touch the volume.
     * Stores the state in window.__mphWant (the YouTube page reads it in onReady) and retries at
     * 1s and 3s for pages whose player/video appears late. The timers read the LATEST
     * __mphWant, so a newer command is never overwritten by an older timer.
     */
    public static String applyVolume(double volume, boolean muted) {
        return "window.__mphWant={v:" + volume + ",m:" + muted + "};" + GENERIC_MPH
                + "(function(){function a(){var w=window.__mphWant;if(!w||!window.mph){return;}"
                + "try{if(w.v>=0){window.mph.setVolume(w.v);}window.mph.setMuted(w.m);}catch(e){}}"
                + "a();setTimeout(a,1000);setTimeout(a,3000);})();";
    }

    /**
     * Starts a once-per-second timer that prints the page's playback state to the console as
     * "MPH-POS position playing rate duration" (read by ClientBrowserSessionManager#onConsole).
     * Safe to run on every page load: the timer is created once per document.
     */
    public static final String REPORTER = GENERIC_MPH + """
            (function(){if(window.__mphRep){return;}
            window.__mphRep=setInterval(function(){try{var m=window.mph;var s=m&&m.state&&m.state();
            if(s){console.log('MPH-POS '+Number(s.p).toFixed(2)+' '+(s.pl?1:0)+' '+s.r+' '+s.d);}}catch(e){}},1000);})();
            """;

    /** Applies a shared playback state: rate, optional seek, then play or pause. Never throws in the page. */
    public static String applyPlayback(double position, boolean playing, double rate, boolean seek) {
        return GENERIC_MPH + "try{var m=window.mph;m.setRate(" + rate + ");"
                + (seek ? "m.seek(" + position + ");" : "")
                + "m." + (playing ? "play" : "pause") + "();}catch(e){}";
    }

    private MphScripts() {
    }
}
