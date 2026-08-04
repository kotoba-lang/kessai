# kessai — rail 非依存の決済ゲートウェイ抽象（純粋データ契約）

**Status: R2**

ISIC 縦割りの actor（信用・証券・保険…）が、actor ごとに決済統合を書く代わりに
`authorize / capture / refund / void` の 1 つの port を持つための capability
library。ネットワーク・I/O なし。金額は取引通貨の**最小単位の整数**
（BigDecimal を前提にしない = `.cljc` として JVM / cljs / SCI / GraalVM で可搬）。

## 成熟度の梯子（この repo における R-tier の意味）

| tier | 意味 | 現在地 |
|---|---|---|
| R0 | 契約のスケッチのみ | — |
| R1 | 純粋な契約 + テスト | — |
| **R2** | **ドメイン契約が一通り揃い、test / lint / CI が緑。git 座標で消費可能。ただし本番の消費者で実証されていない** | **← いまここ** |
| R3 | 実際の消費者が本番でこれを叩いている | 未達 |
| R4 | 本番負荷で実証され、運用記録がある | 未達 |
| R5 | API が安定し、非互換変更に廃止手順がある | 未達 |

**R3 に上がるための不足**: 実際に決済を通した消費者が無い。

## この抽象が**当てはまらない**もの（重要）

**`kessai` は銀行 rail の抽象であって、PSP の抽象ではない。**
`:card` は ISO 8583（`kotoba.kessai.card` → `kotoba.card`）、`:wire` は
ISO 20022 pain.001 + BIC（`kotoba.kessai.wire` → `kotoba.banking`）。

したがって **Stripe Checkout のようなホスト型リダイレクト決済はここに入らない** ——
それは HTTP API であって ISO 8583 でも ISO 20022 でもなく、`authorize/capture` の
2 段（card-present 由来のモデル）にも素直に写らない。実例: murakumo.cloud の
物販は kessai を経由せず Stripe Checkout を直接呼んでいる（ADR-2608040100）。
**PSP を足すなら第 3 の rail として設計する**（`:psp-redirect` 等）のであって、
`:card` に押し込まない。

## 検証

```bash
clojure -M:test   # 10 tests / 87 assertions
clojure -M:lint   # errors 0
```
