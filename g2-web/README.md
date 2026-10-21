# g2-web — Even Hub web app (G2 side)

English | [日本語](README.ja.md)

Sends the PCM from the G2 microphone to **FLXR G2 Companion** ([`../app-g2-companion`](../app-g2-companion/README.md)) on the same phone over `ws://127.0.0.1:18765/ws`, and displays the returned transcription and reply on the G2.
For the protocol and usage, see [`../app-g2-companion/README.md`](../app-g2-companion/README.md).

```bash
npm install
npm run dev        # http://localhost:5173
npm run simulate   # evenhub-simulator (--automation-port 9898)
npm run pack       # → flxr-g2.ehpk
```

- Uses `@evenrealities/even_hub_sdk` 0.0.10, `evenhub-cli` 0.1.13 or later, and `evenhub-simulator` 0.7.3 or later.
- `network.whitelist` in `app.json` contains `http://127.0.0.1:18765` / `ws://127.0.0.1:18765` (and their forms without the port).
- Change `package_id` in `app.json` to your own ID when registering with Even Hub.
- G2 controls: tap = pause/resume the microphone (reconnect when disconnected), double-tap = exit.
- When it cannot connect, the G2 shows the cause (companion not running, connection refused, blocked by CORS or the whitelist, timeout, or port conflict). The cause is determined from the combination of results of a normal fetch and a `mode: 'no-cors'` fetch to `/health` (`diagnose()` in `src/companion.ts`).
- `?transport=http` forces the HTTP fallback (POST /api/pcm + long polling), and `?transport=ws` disables the fallback.
- Does not work with the Even App on iOS (because the companion app cannot be installed there).
