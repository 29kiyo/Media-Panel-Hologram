# mcef-fork

Fork of CinemaMod/mcef (LGPL-2.1-or-later) and CinemaMod/java-cef (BSD-3-Clause).

- mcef:     https://github.com/CinemaMod/mcef      branch 1.21.5  commit adf0ace3bf873bc33ba5084b6944539470f97929
- java-cef: https://github.com/CinemaMod/java-cef  commit eaeb3d4370aa3526ee237ad1981ad59af3de4dd1

Imported paths:
- mcef common/src/main/java       -> mcef-fork/src/main/java
- mcef common/src/main/resources  -> mcef-fork/src/main/resources
- java-cef java/                  -> mcef-fork/jcef/java (plus LICENSE.txt, AUTHORS.txt)

The first commit on this directory is the pristine, unmodified import.
All later local modifications are recorded in the git history.
Note: CefWindowsShutdownMixin.java has no license header upstream.

- Removed: mcef-fork/jcef/java/tests (upstream also excludes it from the build)
- Removed: com/cinemamod/mcef/example/ExampleScreen.java (sample screen, not needed)

Local modifications (not in upstream; each was a fix for a bug reproduced only in actual runs):
- MCEFBrowser#onPaint / #setCursor: CEF calls these from its own render thread and they touched GL/GLFW directly.
  Now checks RenderSystem.isOnRenderThread() and, if off-thread, re-queues via Minecraft.getInstance().execute(...).
- MCEFBrowser: added volatile `closed` flag. Set at the start of close(); onPaint and setCursor return immediately
  when set, so late CEF callbacks cannot touch GL resources that were already released.
- MCEFRenderer: added volatile `textureConfirmedReady` flag. Reset on every (re)creation of the texture and set via
  RenderSystem.queueFencedTask once the GPU has finished; isTextureReady() also requires it.
- MCEFDirectTexture: AbstractTexture's constructor pre-fills `sampler` with a SHARED SamplerCache entry, so the
  upstream-port check `if (sampler == null)` never created our own sampler and releaseTextures() closed the shared one.
  Added `ownsSampler` flag: only a sampler created here is closed. (Symptom: "Sampler for Sampler0 ... has been closed!"
  on any session created after another session was closed.)
- MemoryPageScheme + MCEF.java: registers a scheme handler for https://mph.local/ that serves the YouTube IFrame
  player page from memory (a data: page is rejected by YouTube with error 153).
- MemoryPageScheme routes: /player?v=ID (YouTube), /file?u=URL (plain <video> for direct files); both expose window.mph
  (play/pause/toggle/setVolume/setMuted/setRate/seek/seekBy) for remote control via executeJavaScript.
- Bundled hls.js 1.7.3 (Apache-2.0) under src/main/resources/mph/, served at https://mph.local/hls.min.js for .m3u8.
  See THIRD_PARTY_NOTICES.md at the repository root.
- MemoryPageScheme additional routes: /image?u=URL (contain-scaled image, falls back to the plain page) and
  /player?v=ID&list=PL... (YouTube playlist, queue overlay, window.mph next/prev/toggleShuffle/toggleQueue).
- MemoryPageScheme route /error?c=NAME&u=URL: dark "Failed to load page" screen shown on the block face for network-level load errors
  (shown by ClientBrowserSessionManager#onLoadError; HTTP 4xx/5xx are not replaced).
- /player page: window.mph.toggleCaptions() (IFrame API captions module via loadModule/setOption; undocumented calls, verified in practice).

- CefInitMixin: the scheduled MCEF.initialize() task now returns immediately when MCEF is already initialized
  (several screen changes before startup queued several tasks: repeated 1 s sleeps on the main thread and
  "Settings can only be passed to CEF before createClient is called the first time.").

- MCEF: a shutDown flag (wasShutDown()) is set by shutdown(); initialize() and CefInitMixin's scheduled task do
  nothing afterwards (game exit calls setScreenAndShow after shutdown, which re-ran initialize() on a dead CEF).

- MemoryPageScheme: /player and /file pages gained window.mph.state() (position/playing/rate/duration), read by the
  MPH-POS console reporter (MphScripts.REPORTER).

- MCEFBrowser#setCursor: no-op while no screen is open (gui.screen()==null); glfwSetCursor skipped for a 0 cursor handle (looping video freed the cursor while just looking at it).

## Vulkan texture upload path (not upstream)
- `MCEFRenderer#usesGl()` and `MCEFRenderer#onPaintRegion(...)`: when the browser texture is not a `GlTexture` (Vulkan backend), the dirty region is copied into a tightly packed staging buffer with B/R swapped (CEF delivers BGRA, `RGBA8_UNORM` expects RGBA) and uploaded via `CommandEncoder#writeToTexture`. The staging buffer is freed in `cleanup()`.
- `MCEFRenderer#onPaintFull0`: non-GL branch calls `onPaintRegion` for full-frame uploads.
- `MCEFBrowser#onPaint` / `#paintNonGl`: partial updates take the non-GL path when `!renderer.usesGl()`.
- Limitation: popups (e.g. `<select>` dropdowns) are not drawn on the Vulkan path yet. The OpenGL path is unchanged.
- Tried and reverted: a per-browser `CefRequestContext` (separate zoom/cookies). `https://mph.local/` is registered globally via `CefApp#registerSchemeHandlerFactory` and is not inherited by new contexts (ERR_NAME_NOT_RESOLVED), and the fork's Java API has no per-context registration.
