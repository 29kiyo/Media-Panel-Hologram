# Media Panel & Hologram

[![Modrinth Downloads](https://img.shields.io/modrinth/dt/media-panel-hologram?style=for-the-badge&logo=modrinth&label=Modrinth)](https://modrinth.com/mod/media-panel-hologram) [![CurseForge Downloads](https://img.shields.io/curseforge/dt/1748276?style=for-the-badge&logo=curseforge&label=CurseForge)](https://www.curseforge.com/minecraft/mc-mods/media-panel-hologram) [![GitHub Issues](https://img.shields.io/github/issues/29kiyo/Media-Panel-Hologram?style=for-the-badge&logo=github&label=Issues)](https://github.com/29kiyo/Media-Panel-Hologram/issues)
[日本語](README_ja.md)

Thin panel blocks and holograms that display images, videos, music and web pages inside Minecraft.

- Mod ID: `mediapanelhologram`
- Loaders: Fabric / NeoForge (built with Architectury)
- Minecraft: 26.2 or later
- Requires: Java 25, [GeckoLib](https://geckolib.com/), [Architectury API](https://modrinth.com/mod/architectury-api) (Fabric also requires Fabric API)
- License: LGPL-2.1-or-later

## Features
- **Media Panel**: a thin plate block. Adjacent panels facing the same way merge into one large rectangular screen.
- **Media Block**: four modes (Image / Video / Music / Browser). Can hold another block to camouflage itself.
- **Hologram Block**: shows the content floating in the air, with adjustable position, size, tilt and opacity.
- **Remote Control**: connect several screens and control them together.
- Direct browser control from the block, synchronized playback between players, repeat, lock with allowed players, and a configurable display range.

## Notes
- On first launch the mod **automatically downloads** the browser engine (Chromium Embedded Framework, about 124 MB, about 270 MB unpacked) from `mcef-download.cinemamod.com`. A network connection is required and the mod depends on that server.
- Because any URL can be opened, the browser's same-origin policy (web security) is disabled. Be careful when opening untrusted URLs.
- Please follow the terms of each site you use. The mod does not control advertisements on sites such as YouTube. Spotify cannot be played (DRM).

## Build
Requires JDK 25.

    ./gradlew build

Jars are written to `fabric/build/libs/` and `neoforge/build/libs/` as `mediapanelhologram-<loader>-<version>.jar`.

## Third-party
- MCEF (fork of the CinemaMod version, LGPL-2.1-or-later) and java-cef (BSD-3-Clause)
- hls.js 1.7.3 (Apache-2.0)

See `THIRD_PARTY_NOTICES.md` and `mcef-fork/UPSTREAM.md` for details.

## Supported versions

| Minecraft | Loaders | Notes |
|---|---|---|
| 26.3 | Fabric, NeoForge | NeoForge 26.3 is still in beta. Requires Architectury API 22+. |
| 26.2 | Fabric, NeoForge | Maintained on the `release/26.2` branch. Requires Architectury API 21. |

Jars are named `mediapanelhologram-<loader>-<version>-mc<minecraft>.jar` (for example `mediapanelhologram-fabric-1.0.0-mc26.3.jar`). Use the jar that matches your Minecraft version; 26.2 jars do not run on 26.3.

## Known limitations

- Page zoom is per screen (applied as a device scale factor, with an automatic base zoom per quality preset); Ctrl+/-/0 and Ctrl+wheel are disabled in the embedded browser.
- Cookies and logins are shared between all screens (one browser profile).
- On the Vulkan graphics backend, browser frames and `<select>` popups are uploaded through a separate path; OpenGL is the more tested backend.
- Screens may look jagged when viewed from far away or heavily downscaled (no mipmaps on the browser texture).
