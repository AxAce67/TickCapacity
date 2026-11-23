<div align="center">

<img src=".github/icon.png" width="96" height="96" alt="">

# TickCapacity

Minecraft サーバーの性能を測り、快適に遊べる人数を推定するプラグイン

[![build](https://img.shields.io/github/actions/workflow/status/AxAce67/TickCapacity/ci.yml?branch=main&label=build)](https://github.com/AxAce67/TickCapacity/actions/workflows/ci.yml) [![release](https://img.shields.io/github/v/release/AxAce67/TickCapacity?include_prereleases&label=release)](https://github.com/AxAce67/TickCapacity/releases) [![license](https://img.shields.io/github/license/AxAce67/TickCapacity)](LICENSE)<br>
![Minecraft](https://img.shields.io/badge/Minecraft-1.20.6%E2%80%9326.3-62B47A) ![server](https://img.shields.io/badge/server-Paper%20%7C%20Purpur-1E88E5) ![Java](https://img.shields.io/badge/Java-21%2B-E76F00)

[English](README.md) | **日本語**

</div>

ダウンロード: [GitHub Releases](https://github.com/AxAce67/TickCapacity/releases)

Minecraft サーバー（Paper / Purpur）の性能を測り、**快適に遊べる人数（推奨）**と **20 TPS を保てなくなる人数（限界）**を、
区間と信頼度つきで推定するプラグインです。データが足りないときは、無理に数字を出さずにそう表示します。

```
━━━━ TickCapacity ━━━━
● 快適  TPS 20.0・負荷 24%
人数 12 / 推奨 28 / 限界 49（中）
✔ 問題なし
➤ あと約2日で精度アップ [開く]
[人数] [性能] [表示]
[ベンチ] [レポート] [管理]
```

## 動作環境

- Paper 1.20.6 〜 26.x（Purpur などの派生版も可）
- Java 21 以降（使う Paper のバージョンが必要とするもの）
- 非対応: Spigot（tick の時間を正確に測れないため）、Folia（サーバー全体で1つの tick がないため）

## はじめかた

1. jar を `plugins/` に入れてサーバーを起動します。日本語で表示するには `config.yml` を `language: ja`
   にします（`auto` ならサーバーの言語設定に合わせます）。
2. これだけです。起動3分後から、10秒ごとにデータが記録されます。
3. `/tc` を打ちます。最後の行に、次にやることが表示されます。

人数の推定には2つの方法があります。

| | 実データ | ベンチマーク |
|---|---|---|
| やること | 何もしない（普段どおり運営する） | 誰もいないときに `/tc bench start` |
| かかる時間 | 普段のプレイで数日 | 約3〜6分 |
| 正確さ | 高い（本物のプレイヤーで測るため） | 目安（仮想プレイヤーは本物より軽いため） |

実データが十分に集まると、自動で実データのほうが使われます。ベンチマークは、公開前の目安や、
ハードウェアの比較に向いています。

## コマンド

`/tc`（`/tickcapacity` の別名）から、すべての画面にボタンで移動できます。数値にカーソルを合わせると
詳しい説明が出ます。

| コマンド | 権限 | 内容 |
|---|---|---|
| `/tc` | `tickcapacity.use` | 概要（状態・人数・注意点・次にやること） |
| `/tc capacity [details]` | `tickcapacity.use` | 何人入れるか（実データ・ベンチマーク・遊び方・時間帯） |
| `/tc perf` | `tickcapacity.use` | TPS・処理時間・CPU・メモリ・ワールドの負荷と、重い原因・改善案 |
| `/tc monitor [bossbar\|actionbar\|tab\|off]` | `tickcapacity.use` | 画面に常時表示（プレイヤーごとに記憶） |
| `/tc bench` | `tickcapacity.use` | ベンチマークの説明と前回の結果 |
| `/tc bench start [回数] [force]`、`stop` | `tickcapacity.admin` | ベンチマークの開始・停止 |
| `/tc bench list`、`compare <ID> <ID>` | `tickcapacity.use` | 保存した結果の一覧・比較 |
| `/tc report` | `tickcapacity.admin` | グラフ付きのレポート（Markdown と HTML） |
| `/tc admin reload\|alerts\|alert-test\|calibration-reset\|profile [秒]` | `tickcapacity.admin` | 管理用 |

どちらの権限も、標準では OP だけが使えます。

## 推定のしくみ

### 実データ

10秒ごとのサンプルに、オンライン人数と、tick の処理時間の分布、CPU、メモリ、GC、読み込み中の
チャンク数、エンティティ数、新しく生成されたチャンク数を記録します。

- サンプルを人数ごとにまとめ、それぞれの処理時間（MSPT）の中央値を使います。自動保存などの一時的な
  重さに引きずられないようにするためです。
- 重み付きの直線 `MSPT = 基礎負荷 + k × 人数` を当てはめます。データが十分にあれば上向きに曲がる
  モデルも試し、明らかに合うほうを使います。限界の近くで急に重くなるサーバーでも、限界人数が
  甘くなりにくくなります。
- **推奨**: 平均 MSPT 30 ms 以下、かつ上位5%が 50 ms 以下。**限界**: 平均 MSPT 50 ms。
- 区間は、回帰の信頼帯と、1時間ごとのまとまりで再サンプリングするブートストラップの、広いほうを
  使います。同じ1時間のサンプルは似ているため、それを無視すると区間が狭くなりすぎるからです。
- 信頼度は、実測した最大人数からどれだけ離れた予測か、人数の種類の多さ、区間の幅、データの日数で
  決まります（3日未満なら最大でも「中」）。

`/tc capacity` では、遊び方（探索が少ない日・普段・探索が多い日）による違いと、混む時間帯も見られます。

### ベンチマーク

`/tc bench start` を実行すると、専用のワールド（`tickcapacity_benchmark`、シード固定）に仮想プレイヤーを
増やしていきます。仮想プレイヤー1人は、simulation-distance と同じ広さのチャンクと、45体の Mob です（設定で変更可）。

- 段階の刻みは自動で決まります。余裕があるうちは大きく、限界が近づくと細かくなります。
- 新しいチャンクを読み込んだあと、サーバーが落ち着くのを待ってから測ります。
- 平均 MSPT が限界に達する、メモリにそれ以上の人数が収まらない、設定の最大人数（40人）に達する、の
  いずれかで終わります。人数を増やした直後に重くなりすぎた場合は、増やした分を外して小さい刻みで
  やり直します。これらは正常な終わり方で、どの限界で止まったかが結果に表示されます。
- 自分以外のプレイヤーが入ってきたとき（`force` を付けた場合を除く）と、メモリが本当に足りなく
  なりそうなときは、すぐに止まります。
- Mob はディスクに保存されず、いつものワールドには一切触れません。終わったあとも専用ワールドは
  空のまま残し、次回は地形を使い回します。
- `/tc bench start 3` で、3回分をまとめて区間を狭くできます。

**限界について**: 本物のプレイヤーが近くにいないと、Minecraft は Mob の湧きやランダムティックを
省きます。そのため仮想プレイヤーは本物より軽く、十数人を超えると負荷が増えなくなることがよく
あります。プラグインはこれを自動で見分けて、信頼度を「低」の参考値として表示します。正確な人数は、
実データのほうが確実です。

ベンチマークを本物に近づけるには、`spigot.yml` に次の設定を足して再起動してください。

```yaml
world-settings:
  tickcapacity_benchmark:
    entity-activation-range:
      animals: 0
      monsters: 0
      raiders: 0
      misc: 0
```

### キャリブレーション（補正）

実データの信頼度が「中」以上になり、同じハードウェア・同じ view/simulation distance でのベンチマークが
あると、本物と仮想プレイヤーの1人あたりの負荷の比を `calibration.json` に保存します。以降のベンチマーク
（たとえばハードウェアを変えたあと）は、この比で本物の人数に換算されます。

## 常時表示とアラート

`/tc monitor` で、ボスバー・アクションバー・タブリストに `TPS 20.0 | MSPT 12.3 ms | 14/28`
（オンライン / 推奨）を表示します。ベンチマークの進み具合も同じ場所に出せます。

処理の重い状態が続いたとき、回復したとき、人数が推奨・限界を超えたとき（信頼度「中」以上の場合のみ）に、
オンラインの管理者・コンソール・Discord（任意）へ通知します。`/tc admin alert-test` で試せます。

## 設定

`plugins/TickCapacity/config.yml` の各項目にコメントがあります。よく変えるのは次の項目です。

| 項目 | 標準 | |
|---|---|---|
| `language` | `auto` | `auto`・`en`・`ja` |
| `estimation.comfortable-mspt` | `30.0` | 快適とみなす平均処理時間 |
| `estimation.limit-mspt` | `50.0` | 20 TPS を保てなくなる平均処理時間 |
| `benchmark.max-virtual-players` | `40` | ベンチマークの最大人数 |
| `benchmark.movement-blocks-per-second` | `0` | 仮想プレイヤーを歩かせて地形を生成させる（例: `4.3`） |
| `benchmark.progress-display` | `[bossbar]` | `bossbar`・`actionbar`・`tab` から選ぶ |
| `monitor.default-display` | `[bossbar]` | 引数なしの `/tc monitor` で使う表示先 |
| `alerts.mspt-threshold` | `45.0` | 30秒平均がこれを超え続けたら通知 |
| `alerts.discord-webhook` | 空 | Discord の Webhook URL |

## 対応バージョン

Paper 1.20.6 の API でビルドしています。新しい機能は起動時に検出して、あれば使います
（ログの `Server features: ...` で確認できます）。

| 機能 | 使うバージョン | それより古い場合 |
|---|---|---|
| 無人時の一時停止への対応 | 1.21.2+ | 一時停止しないので不要 |
| `GameRules` の定数 | 26.x | 従来の `GameRule` の定数 |
| `Mob#setPersistenceRequired` | 26.3+ | `setRemoveWhenFarAway(false)` |
| 権限がない人にコマンドを隠す | 1.21+ | 権限の確認はするが、Tab 補完には出る |

1.20.6、1.21.1、1.21.4、1.21.8、1.21.11、26.1.2、26.2、26.3 で動作を確認しています。

## データファイル

```
plugins/TickCapacity/
├── config.yml
├── samples/YYYY-MM-DD.csv      サンプル（1日1ファイル、30日で削除）
├── results/<id>.json           ベンチマーク結果
├── calibration.json            ベンチマークの補正値
└── reports/report-<日時>.md / .html
```

## ビルドとテスト

```bash
./gradlew build                                   # コンパイル・単体テスト・build/libs/TickCapacity-<version>.jar
./gradlew runServer                               # プラグイン入りのテスト用 Paper サーバー
scripts/smoke-test.sh 1.20.6 1.21.4 26.3          # 実サーバーで全コマンドと短いベンチマークを試す
```

起動テストは Paper を `run/smoke/<version>/` にダウンロードします（チェックサムを確認）。GitHub Actions
では、単体テストと、対応する全バージョンでの起動テストを実行します（26 より前は Java 21 で動かします）。

| パッケージ | |
|---|---|
| `core` | 統計と推定。Bukkit に依存せず、単体テストあり |
| `bench` | ベンチマーク（進行、仮想プレイヤー、地形の読み込み、ワールド管理） |
| `monitor`、`sampling` | tick の計測、JVM の指標、CSV への記録 |
| `capacity` | 推定結果のキャッシュと補正 |
| `display`、`alert` | ボスバー・アクションバー・タブリスト、アラート |
| `report`、`command` | チャット画面、レポート、`/tc` コマンド |
| `compat` | サーバーのバージョンごとの違いの吸収 |

## ライセンス

[MIT](LICENSE)
