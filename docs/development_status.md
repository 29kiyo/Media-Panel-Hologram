# Development Status

## 現在のフェーズ
Phase 0: 設計フェーズ(完了)

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

## 未確定事項(Phase1以降で決定)
- [ ] Modrinth/CurseForgeへの自動公開有無(Phase9で検討)
- [ ] mcef-fork/ サブプロジェクトの具体的なディレクトリ/ビルド構成

## 次フェーズ
Phase 1: プロジェクト基盤構築(docs/phase1_design.md 参照)
