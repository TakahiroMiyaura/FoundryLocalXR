// Copyright (c) 2026 Takahiro Miyaura
// SPDX-License-Identifier: BSL-1.0
// Distributed under the Boost Software License, Version 1.0.
// See the LICENSE file in the project root.

/** Callbacks for the phone-side controls. */
export interface UiHandlers {
  onReconnect: () => void
  onToggleMic: () => void
  onAsk: (text: string) => void
  onUseHttp: () => void
}

const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T

const LOG_LINES = 80
const lines: string[] = []

/** Renders the phone-side page into `#app`. */
export function mountUi(h: UiHandlers, version: string): void {
  document.querySelector<HTMLDivElement>('#app')!.innerHTML = `
    <div style="padding:14px;display:flex;flex-direction:column;gap:10px;font-size:14px">
      <div style="font-size:20px;font-weight:600">FLXR G2 Local AI <span style="font-size:12px;color:#7f8c99">v${version}</span></div>
      <div id="st" style="padding:8px;border-radius:8px;background:#1b232b">起動中…</div>
      <div style="display:flex;gap:8px;flex-wrap:wrap">
        <button id="re">再接続 / 診断</button>
        <button id="mic">マイク一時停止</button>
        <button id="http">HTTP fallback で接続</button>
      </div>
      <div style="display:flex;gap:8px">
        <input id="q" placeholder="テキストで質問（疎通確認）" style="flex:1;padding:6px" />
        <button id="ask">送信</button>
      </div>
      <div><b>あなた:</b> <span id="you"></span></div>
      <div><b>AI:</b> <span id="ai"></span></div>
      <details open><summary>診断</summary><pre id="diag" style="white-space:pre-wrap;overflow-wrap:anywhere;font-size:12px;color:#b0bec5"></pre></details>
      <details><summary>ログ</summary><pre id="log" style="white-space:pre-wrap;overflow-wrap:anywhere;font-size:11px;color:#90a4ae"></pre></details>
    </div>`
  $('re').onclick = h.onReconnect
  $('mic').onclick = h.onToggleMic
  $('http').onclick = h.onUseHttp
  $('ask').onclick = () => {
    const q = $<HTMLInputElement>('q')
    if (q.value.trim()) h.onAsk(q.value.trim())
    q.value = ''
  }
}

/** Sets the status line text and color. */
export function setStatus(text: string, tone: 'ok' | 'warn' | 'error' = 'ok'): void {
  const el = $('st')
  el.textContent = text
  el.style.background = tone === 'ok' ? '#1b2b22' : tone === 'warn' ? '#2b281b' : '#2b1b1b'
}

/** Updates the mic button label. */
export function setMicLabel(paused: boolean): void {
  $('mic').textContent = paused ? 'マイク再開' : 'マイク一時停止'
}

/** Shows the latest user utterance. */
export function setYou(text: string): void {
  $('you').textContent = text
}

/** Shows the latest assistant reply. */
export function setAi(text: string): void {
  $('ai').textContent = text
}

/** Shows diagnostics as key/value lines. */
export function setDiagnostics(d: Record<string, unknown>): void {
  $('diag').textContent = Object.entries(d)
    .map(([k, v]) => `${k}: ${typeof v === 'object' ? JSON.stringify(v) : String(v)}`)
    .join('\n')
}

/** Appends a line to the on-page log and the console. */
export function logLine(line: string): void {
  const t = new Date().toLocaleTimeString()
  lines.push(`${t} ${line}`)
  while (lines.length > LOG_LINES) lines.shift()
  const el = document.getElementById('log')
  if (el) el.textContent = lines.slice().reverse().join('\n')
  console.log(`[flxr-g2] ${line}`)
}
