# Media Panel & Hologram

薄いパネルブロックとホログラムで、画像・動画・音楽・Webページを Minecraft 内に表示する MOD。

- Mod ID: `mediapanelhologram`
- 対応ローダー: Fabric / NeoForge (Architectury)
- 対応 Minecraft: 26.2 以降
- 必須: Java 25、[GeckoLib](https://geckolib.com/)、[Architectury API](https://modrinth.com/mod/architectury-api)(Fabric は Fabric API も必須)
- ライセンス: LGPL-2.1-or-later

## 機能
- **Media Panel**: 薄い板状ブロック。隣接する同じ向きのパネルは長方形に合体して1つの大画面になる
- **Media Block**: 画像 / 動画 / 音楽 / ブラウザの4モード。ブロックを収納してカモフラージュできる
- **Hologram Block / ホログラム**: 位置・大きさ・傾き・透過度を設定して空中に表示
- **リモコン**: 複数のブロックを接続してまとめて操作
- ブロック単体でのブラウザ操作、再生同期(複数プレイヤーで同じ位置を再生)、リピート、ロックと許可プレイヤー、表示範囲の設定

## 初回起動時の注意
- ブラウザエンジン(Chromium Embedded Framework)を、初回起動時に `mcef-download.cinemamod.com` から**自動ダウンロード**します(約124MB、展開後 約270MB)。ネットワーク接続が必要で、このサーバーに依存します。
- 任意のURLを開けるため、ブラウザのセキュリティ設定(同一オリジンポリシー)を緩めています。信頼できないURLを開くときは注意してください。
- 公式サイトの利用規約に従って使ってください。YouTube等の広告をMODは制御しません。Spotify はDRMのため再生できません。

## ビルド
JDK 25 が必要です。

    ./gradlew build

成果物は `fabric/build/libs/` と `neoforge/build/libs/` の `mediapanelhologram-<loader>-<version>.jar` です。

## サードパーティ
- MCEF(CinemaMod版のフォーク、LGPL-2.1-or-later)と java-cef(BSD-3-Clause)
- hls.js 1.7.3(Apache-2.0)
詳細は `THIRD_PARTY_NOTICES.md` と `mcef-fork/UPSTREAM.md` を参照してください。
