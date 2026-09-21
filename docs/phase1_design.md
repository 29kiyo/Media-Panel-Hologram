# Phase 1: プロジェクト基盤構築

## ゴール
Architectury(Fabric+NeoForge, MC26.2以降)のビルド可能な骨組みを作り、CI(ビルドのみ)を通す。

## タスク
- [ ] Architectury Templateプラグイン(IntelliJ or https://architectury.dev/templates/)でmultiloaderテンプレート生成(common/fabric/neoforge構成)
- [ ] Group ID / Package名 / Mod ID(mediapanelhologram)確定・反映
- [ ] MC 26.2対応バージョンのMapping(Mojmap想定)・Java要求バージョンを確認
- [ ] GeckoLib依存追加(Fabric/NeoForge双方の対応バージョンを事前調査)
- [ ] MCEF(ブラウザ埋め込み)の対応バージョン・ライセンス・ビルド方法を調査(結果はphase4_design.mdに反映)
- [ ] .gitignore, README雛形作成
- [ ] .github/workflows/ci.yml(PR/pushごとに ./gradlew build のみ実行するビルド確認用CI。リリース自動化はPhase9で別途build.ymlとして作成)
- [ ] IntelliJで開いて実行確認(runClient等のGradleタスク疎通確認)

## 調査が必要な項目(次チャット開始時にgrep/catで確認すること)
- 既存ディレクトリ構成の有無
- 使用中のGradleラッパーバージョン

## MCEF調査結果・方針(Phase0確定: B案)
- CinemaMod版MCEF(com.cinemamod:mcef, LGPL-2.1-or-later)をフォークして採用。元々Fabric/NeoForge両対応の構成を持つため、26.2向けの移植はMinecraft統合部分(mixin等)の追従のみで済む見込み。
- **モノレポ構成で統合**: 別リポジトリには分離せず、本体リポジトリ内にGradleサブプロジェクト(例: mcef-fork/)として取り込む。
- プロジェクト全体のライセンスはLGPL-2.1-or-laterに統一(MCEFフォーク部分との整合のため)。
- 本体側はIBrowserEngine等の自作抽象化レイヤー経由でのみMCEF機能を呼び出す設計とする(将来のバージョン更新の影響範囲を限定するため)。
- 本家の更新検知自動化は行わない。バージョン更新は開発者が手動で判断し追従する。

## MCEFフォーク移植タスク
- [ ] 本体リポジトリ内に mcef-fork/ サブプロジェクトを作成し、CinemaMod/mcefのソースを取り込む
- [ ] settings.gradle(.kts) にサブプロジェクトとして登録
- [ ] MC26.2向けのMixin/API変更点を洗い出す(タイムボックス: 目安1〜2日で見積もる)
- [ ] Fabric platform moduleの26.2追従
- [ ] NeoForge platform moduleの26.2追従
- [ ] 本体プロジェクト側にIBrowserEngine抽象化インターフェースを定義
- [ ] 想定工数を大幅に超える場合のみ、MCEF Modern依存(Fabric限定)へのフォールバックを検討
- [ ] 結果をdocs/development_status.mdに反映
- [ ] LICENSEファイル(LGPL-2.1-or-later全文)をルートに配置
