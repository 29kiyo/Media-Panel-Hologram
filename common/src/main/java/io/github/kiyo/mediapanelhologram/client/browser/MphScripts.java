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
              setRepeat:function(x){window.__mphRepWant=!!x;
                if(!window.__mphRepHook){window.__mphRepHook=true;
                  document.addEventListener('play',function(e){var t=e.target;
                    if(t&&('loop' in t)){t.loop=!!window.__mphRepWant&&!document.querySelector('.ad-showing');}},true);setInterval(function(){var q=vid();if(q){q.loop=!!window.__mphRepWant&&!document.querySelector('.ad-showing');}},500);}
                var v=vid();if(v){v.loop=!!x&&!document.querySelector('.ad-showing');}},
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
            (function(){if(window.__mphRepTimer){return;}
            window.__mphRepTimer=setInterval(function(){try{var m=window.mph;var s=m&&m.state&&m.state();
            if(s){console.log('MPH-POS '+Number(s.p).toFixed(2)+' '+(s.pl?1:0)+' '+s.r+' '+s.d);}}catch(e){}},1000);})();
            """;

    /**
     * Tells the client whether a password-like field has the focus ("MPH-PWD 1" / "MPH-PWD 0", read by
     * ClientBrowserSessionManager#onConsole). Counts as 1: input[type=password], autocomplete containing
     * "password", text masked with -webkit-text-security, and any focused iframe/frame/object/embed (its
     * content cannot be inspected). Looks into open shadow roots. Always reports the current state once
     * per injection; the listeners are installed once per document.
     */
    public static final String PWD_WATCH = """
            (function(){
            function deep(){var e=document.activeElement;
              while(e&&e.shadowRoot&&e.shadowRoot.activeElement){e=e.shadowRoot.activeElement;}return e;}
            function bad(e){if(!e){return false;}var t=(e.tagName||'').toUpperCase();
              if(t==='IFRAME'||t==='FRAME'||t==='OBJECT'||t==='EMBED'){return true;}
              if(t!=='INPUT'){return false;}
              if(((e.type||'')+'').toLowerCase()==='password'){return true;}
              if(((e.getAttribute('autocomplete')||'')+'').toLowerCase().indexOf('password')>=0){return true;}
              try{var s=getComputedStyle(e);var x=s.webkitTextSecurity||s.getPropertyValue('-webkit-text-security');
                if(x&&x!=='none'){return true;}}catch(_){}
              return false;}
            function login(e){if(!e){return false;}
              if(((e.tagName||'')+'').toUpperCase()!=='INPUT'){return false;}
              var ty=((e.type||'')+'').toLowerCase();
              if(ty==='email'||ty==='tel'){return true;}
              var s=[e.name,e.id,e.getAttribute('autocomplete'),e.getAttribute('placeholder'),e.getAttribute('aria-label')].join(' ');
              if(/(user|mail|login|signin|sign-in|account|passw|pwd|otp|2fa|verif|credential|phone|tel)/i.test(s)){return true;}
              try{var f=e.form;if(f&&f.querySelector('input[type=password]')){return true;}}catch(_){}
              return false;}
            function rep(force){var e0=deep();var v=(bad(e0)||login(e0))?1:0;
              if(force||v!==window.__mphPwdLast){window.__mphPwdLast=v;console.log('MPH-PWD '+v);}}
            if(!window.__mphPwdW){window.__mphPwdW=true;
              document.addEventListener('focusin',function(){rep(false);setTimeout(function(){rep(false);},0);},true);
              document.addEventListener('focusout',function(){setTimeout(function(){rep(false);},0);},true);
              window.addEventListener('blur',function(){setTimeout(function(){rep(false);},0);},true);
              setInterval(function(){rep(false);},300);}
            rep(true);})();
            """;

    /** Applies a shared playback state: rate, optional seek, then play or pause. Never throws in the page. */
    public static String applyPlayback(double position, boolean playing, double rate, boolean seek) {
        return GENERIC_MPH + "try{var m=window.mph;m.setRate(" + rate + ");"
                + (seek ? "m.seek(" + position + ");" : "")
                + "m." + (playing ? "play" : "pause") + "();}catch(e){}";
    }

    /**
     * Applies the persisted repeat flag. Stores it in window.__mphRepWant and retries at 1s and 3s;
     * the timers read the LATEST value, so an older timer never overwrites a newer command.
     */
    public static String applyRepeat(boolean repeat) {
        return "window.__mphRepWant=" + repeat + ";" + GENERIC_MPH
                + "(function(){function a(){try{window.mph.setRepeat(window.__mphRepWant);}catch(e){}}"
                + "a();setTimeout(a,1000);setTimeout(a,3000);})();";
    }

    private MphScripts() {
    }
}
