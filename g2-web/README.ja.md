# g2-web — Even Hub の Web アプリ（G2 側）

[English](README.md) | 日本語

G2 のマイクの PCM を、同じスマホの上の **FLXR G2 Companion**（[`../app-g2-companion`](../app-g2-companion/README.ja.md)）に `ws://127.0.0.1:18765/ws` で送り、返ってきた文字起こしと返事を G2 に表示します。
プロトコルと使い方は [`../app-g2-companion/README.ja.md`](../app-g2-companion/README.ja.md) を参照してください。

```bash
npm install
npm run dev        # http://localhost:5173
npm run simulate   # evenhub-simulator（--automation-port 9898）
npm run pack       # → flxr-g2.ehpk
```

- `@evenrealities/even_hub_sdk` 0.0.10、`evenhub-cli` 0.1.13 以降、`evenhub-simulator` 0.7.3 以降を使っています。
- `app.json` の `network.whitelist` に `http://127.0.0.1:18765` / `ws://127.0.0.1:18765`（とポートなしの形）を入れています。
- `app.json` の `package_id` は、Even Hub に登録するときに自分の ID に変えてください。
- G2 の操作：タップ = マイクの一時停止と再開（未接続のときは再接続）、ダブルタップ = 終了。
- 接続できないときは、G2 に原因（companion が起動していない、接続を拒否された、CORS や whitelist で拒否された、タイムアウト、ポートの競合）を表示します。`/health` への通常の fetch と `mode: 'no-cors'` の fetch の結果の組み合わせで判定しています（`src/companion.ts` の `diagnose()`）。
- `?transport=http` で HTTP フォールバック（POST /api/pcm ＋ロングポーリング）を強制し、`?transport=ws` でフォールバックを無効にします。
- iOS の Even App では動きません（companion アプリを置けないため）。
