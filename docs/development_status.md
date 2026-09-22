# Development Status

## 現在のフェーズ
Phase 1: プロジェクト基盤構築(基盤構築は完了。MCEFタイムボックス検証も成功。NeoForge起動確認とspikeのmain統合が残タスク)

## 確定済み(全項目)
- Mod名称: Media Panel & Hologram
- Mod ID: mediapanelhologram
- Group ID: io.github.kiyo.mediapanelhologram
- 技術基盤: Architectury(Fabric+NeoForge, MC26.2以降)
- モデリング: Blockbench + GeckoLib
- LICENSE: LGPL-2.1-or-later(プロジェクト全体)
- ブラウザエンジン: CinemaMod版MCEFをフォークし、本体リポジトリ内にモノレポ構成(mcef-fork/サブプロジェクト)で統合。別リポジトリ分離なし。本家更新検知の自動化も行わない(手動追従)。
- パネル合体上限サイズ: デフォルト16x16、configでそれ以上にも変更可能
- 動的メディア解像度: 実解像度表記(480p/720p/1080p/1440p/4K)、デフォルト1080p
- 静的テクスチャ解像度: 16x16(バニラ準拠)
- 操作権限と破壊権限: 分離。破壊権限は本MODで実装せず既存の保護MOD/vanilla権限に委ねる
- 各アイテムにクラフトレシピを追加する方針
- パネルブロックはレッドストーン信号でオン/オフ切替(信号なし=常時ON)
- ブラウザ直接操作モードはリモコン無しでも単体操作可能、任意のサイトを表示可能(ホワイトリストは任意のオプション機能)
- 広告ブロック機能は実装しない(規約リスク回避、表示インジケーターのみ)
- 全フェーズの詳細設計は docs/ に用意済み

## Phase 1: プロジェクト基盤構築の進捗

### 完了(main にマージ済み)
- Architectury multiloaderテンプレート(common/fabric/neoforge)を導入・統合
- Group ID / Package名 / Mod ID 反映済み、プレースホルダ(Example*, CC0ライセンス等)を本MOD向けに置換
- LICENSE(LGPL-2.1-or-later全文)、README、.gitignore/.gitattributes配置
- .github/workflows/ci.yml(ビルド確認用CI、Java 25)。直近3コミットでCI成功確認済み
- GeckoLib 5.5.5 を common/fabric/neoforge に依存追加(座標: com.geckolib:geckolib-{common,fabric,neoforge}-26.2:5.5.5、リポジトリ: Cloudsmith)
  - 5.5.6 はfabric版が未公開(404)のため5.5.5を採用。将来5.5.6のfabric版が出たら追従を検討
- NeoForge を 26.2.0.52-beta → 26.2.0.88 に更新(GeckoLib 5.5.5がNeoForge 26.2.0.57以上を要求するため)
- Fabric / NeoForge 双方で `runClient` によるMOD読み込み・GeckoLib読み込みを確認済み

### MCEFタイムボックス検証: 完了・成功(spike/mcef-26.2ブランチ、mainに未マージ)
Phase0で決定した「B案: CinemaMod版MCEFフォークをmcef-fork/サブプロジェクトとしてモノレポ統合」の実現可能性を検証。想定工数(目安1〜2日)を大幅に超えず、当日中に2日目相当の到達点(ブラウザのフレームがテクスチャに継続描画される)まで到達。**フォールバック(MCEF Modern依存)は不要と判断。**

- 取り込み元: CinemaMod/mcef ブランチ`1.21.5`、CinemaMod/java-cef(コミットハッシュは`mcef-fork/UPSTREAM.md`に記録)
- ライセンス確認: ソースヘッダ27件で「LGPL-2.1 or (at your option) any later version」を確認済み(1ファイルのみヘッダなし、UPSTREAM.mdに記録)。java-cef本体はBSD-3-Clause
- CEFバイナリ配布: 現状 mcef-download.cinemamod.com に依存(継続の可否はPhase4で判断)。Windows/Linux/macOS(amd64/arm64)のビルド存在を確認済み。ダウンロードサイズ約124MB(展開後約270MB)
- 26.2向けの主なAPI変更対応(すべてspikeブランチで実施済み):
  - `ResourceLocation` → `net.minecraft.resources.Identifier`
  - `Window.getWindow()` → `Window.handle()`
  - `Minecraft.setScreen` → `setScreenAndShow`
  - `Minecraft.screen`(フィールド) → `Minecraft.gui.screen()`(Guiクラスに移動)
  - `GuiGraphics`/`Screen.render` → `GuiGraphicsExtractor`/`Screen.extractRenderState`、`renderBackground`→`extractBackground`
  - `TextureFormat` → `com.mojang.blaze3d.GpuFormat`(RGBA8は`RGBA8_UNORM`)
  - `GpuTexture.setTextureFilter/setAddressMode` 廃止 → `GpuSampler`として分離(`GpuDevice.createSampler`で都度新規生成、呼び出し側が close する必要あり)
  - `AbstractTexture.defaultBlur` 廃止 → texture/textureView/samplerの3フィールド構成に変更
  - 消滅した画面クラス(`ReceivingLevelScreen`, `EditServerScreen`, `EditGameRulesScreen`)は削除、後継候補(`ManageServerScreen`, `AbstractGameRulesScreen`)を追加(要動作検証)
  - `mcef.mixins.json`: `refmap`削除、`compatibilityLevel`を`JAVA_25`に変更
  - java-cef commitハッシュの取得方法をMANIFEST/gitサブモジュール参照から`mcef-fork/src/main/resources/mcef-java-cef-commit.txt`(リソースファイル)参照に変更(モノレポ構成でサブモジュールを使わないため)
- **設計変更点: `MCEFDirectTexture`の実装方式。** 上流は既存GLテクスチャIDを`GlTexture`サブクラスで偽装して包む方式だったが、26.2の`GlTexture`コンストラクタが`FrameBufferCache`を要求するため踏襲不可。`MCEFRenderer`が本物の`GpuTexture`を所有し、`MCEFDirectTexture`は`GpuTextureView`と`GpuSampler`のみを保持・close する設計に変更(テクスチャ自体はrendererが close)
- Fabric jarへの同梱・Mixin登録・`:fabric:build`成功を確認
- Fabric `runClient` でCEF初期化(`Chromium Embedded Framework initialized`)を確認
- **開発環境限定のテスト画面(`MCEFTestScreen`、環境変数`MPH_MCEF_TEST=1`で有効化)でブラウザの描画確認に成功。** ログで`MCEF test: first frame ready, texture 480x270`を確認、目視でも数字カウンターが継続更新されることを確認済み

#### 検証中に判明した重要な注意点(次フェーズ以降で対応)
- **CEF初期化は画面遷移(`setScreenAndShow`呼び出し)に依存する。** `CefInitMixin`は`setScreenAndShow`にフックしており、アイドル状態のタイトル画面では何もトリガーが起きず、初期化が進まない。本実装(Phase4)でリモコン/ブロックGUIを開く操作が自然なトリガーになる想定だが、初回起動時(まだプレイヤーが何も操作していない状態)の扱いは設計が必要
- **`GpuSampler`はcreateSampler()の都度新規生成される(共有キャッシュではない)ことをバイトコード確認済み。** closeし忘れるとGLサンプラーがリークするため、確保元(`MCEFDirectTexture`)が確実に close する設計にしてある
- **`extractBackground`(旧renderBackground)は「1フレームに1回しかぼかせない」制約がある。** 他の画面遷移と競合するタイミングで背景ぼかしを呼ぶと`IllegalStateException: Can only blur once per frame`でクラッシュする。spikeのテスト画面ではぼかし無しの単純塗りつぶしに変更して回避。本実装のGUI(パネルGUI、リモコンGUI等)を作る際は、ネストした画面遷移中の呼び出しに注意
- `--disable-web-security`スイッチが上流のCefUtil初期化コードに残っている。異なるオリジン間のアクセス制限を切るスイッチのため、Phase4までに要否を再検討
- `Failed to download JCEF hash`のWARNが初回起動時に毎回出る(原因未特定、動作には影響なし)
- `CefWindowsShutdownMixin`(jcef_helper.exeの強制終了)はWindows限定のワークアラウンドとして上流のまま維持

#### 未対応(spikeブランチのTODOコメント含む)
- NeoForgeへの同梱・起動確認(Fabricと同様の対応が必要、未着手)
- 消えた画面クラスの後継として追加した`ManageServerScreen`/`AbstractGameRulesScreen`の動作検証(コンパイルは通るが実際にMCEFが正しく反応するかは未確認)
- spike/mcef-26.2 ブランチのコミット履歴整理とmainへのマージ判断

## 未確定事項(Phase1以降で決定)
- [ ] Modrinth/CurseForgeへの自動公開有無(Phase9で検討)
- [ ] mcef-fork/ の最終的なディレクトリ/ビルド構成の確定(spikeの構成で問題なければ確定)
- [ ] CEFバイナリの配布元をCinemaModサーバーのまま使うか自前ホストに切り替えるか(Phase4)
- [ ] `--disable-web-security`スイッチの要否(Phase4)

## 次フェーズ
Phase 1の残タスク完了後、Phase 2: アセット制作(docs/phase2_design.md参照)へ。
