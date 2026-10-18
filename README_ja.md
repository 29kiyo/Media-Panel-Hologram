# Media Panel & Hologram

[English](README.md)

薄いパネルブロックとホログラムで、画像・動画・音楽・Webページを Minecraft 内に表示する MOD。

- Mod ID: `mediapanelhologram`
- 対応ローダー: Fabric / NeoForge (Architectury)
- 対応 Minecraft: 26.2 以降
- 必須: Java 25、[GeckoLib](https://geckolib.com/)、[Architectury API](https://modrinth.com/mod/architectury-api)(Fabric は Fabric API も必須)
- ライセンス: LGPL-2.1-or-later

## 機能
- **Media Panel**: 薄い板状ブロック。隣接する同じ向きのパネルは長方形に合体して1つの大画面になる
- **Media Block**: 画像 / 動画 / 音楽 / ブラウザの4モード。ブロックを収納してカモフラージュできる
- **Hologram Block**: 位置・大きさ・傾き・透過度を設定して、空中に表示
- **リモコン**: 複数の画面を接続してまとめて操作
- ブロック単体でのブラウザ操作、複数プレイヤーでの再生同期、リピート、ロックと許可プレイヤー、表示範囲の設定

## 注意事項
- 初回起動時、ブラウザエンジン(Chromium Embedded Framework、約124MB、展開後 約270MB)を `mcef-download.cinemamod.com` から**自動ダウンロード**します。ネットワーク接続が必要で、このサーバーに依存します。
- 任意のURLを開けるため、ブラウザのセキュリティ設定(同一オリジンポリシー)を無効にしています。信頼できないURLを開くときは注意してください。
- 利用するサイトの規約に従ってください。YouTube等の広告をMODは制御しません。SpotifyはDRMのため再生できません。

## ビルド
JDK 25 が必要です。

    ./gradlew build

成果物は `fabric/build/libs/` と `neoforge/build/libs/` の `mediapanelhologram-<loader>-<version>.jar` です。

## サードパーティ
- MCEF(CinemaMod版のフォーク、LGPL-2.1-or-later)と java-cef(BSD-3-Clause)
- hls.js 1.7.3(Apache-2.0)

詳細は `THIRD_PARTY_NOTICES.md` と `mcef-fork/UPSTREAM.md` を参照してください。

## 対応バージョン

| Minecraft | ローダー | 備考 |
|---|---|---|
| 26.3 | Fabric, NeoForge | NeoForge 26.3はまだbetaです。Architectury API 22以降が必要です。 |
| 26.2 | Fabric, NeoForge | `release/26.2`ブランチで保守しています。Architectury API 21が必要です。 |

jarの名前は`mediapanelhologram-<ローダー>-<バージョン>-mc<Minecraft>.jar`です(例: `mediapanelhologram-fabric-1.0.0-mc26.3.jar`)。Minecraftのバージョンに合うjarを使ってください。26.2用のjarは26.3では動きません。

## 既知の制限

- ページのズームは画面ごとです(デバイススケール係数として適用し、画質プリセットごとに基準倍率を自動設定します)。埋め込みブラウザ内のCtrl+/-/0とCtrl+ホイールは無効です。
- CookieとログインはすべてのScreenで共有されます(ブラウザプロファイルは1つ)。
- Vulkanバックエンドでは、ブラウザ映像と`<select>`のポップアップを別経路で転送しています。検証が進んでいるのはOpenGLです。
- 遠くから見たときや大きく縮小されたとき、画面の縁や文字がぎざぎざに見えることがあります(ブラウザテクスチャにミップマップがないため)。
