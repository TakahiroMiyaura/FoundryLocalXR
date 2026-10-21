// Copyright (c) 2026 Takahiro Miyaura
// SPDX-License-Identifier: BSL-1.0
// Distributed under the Boost Software License, Version 1.0.
// See the LICENSE file in the project root.

export const COMPANION_HOST = '127.0.0.1'
export const COMPANION_PORT = 18765
export const COMPANION_HTTP = `http://${COMPANION_HOST}:${COMPANION_PORT}`
export const COMPANION_WS = `ws://${COMPANION_HOST}:${COMPANION_PORT}/ws`
const SERVICE_NAME = 'flxr-g2-companion'

/** Assistant status reported by the companion. */
export interface CompanionStatus {
  ready: boolean
  listening: boolean
  status: string
  error: string | null
  models: string
  audioOpen: boolean
}

/** JSON event pushed by the companion. */
export type CompanionEvent =
  | ({ type: 'hello' | 'status'; seq?: number; reason?: string } & CompanionStatus)
  | { type: 'partial' | 'transcript' | 'reply_delta' | 'reply'; seq?: number; text: string }
  | { type: 'error'; message: string }
  | { type: 'ack'; of: string }
  | { type: 'pong'; t?: string }

/** Result category of a companion reachability check. */
export type DiagnosisKind = 'ok' | 'cors' | 'unreachable' | 'timeout' | 'foreign'

/** Result of {@link diagnose}. */
export interface Diagnosis {
  kind: DiagnosisKind
  ms: number
  detail: string
  health?: Record<string, unknown>
}

const HEALTH_TIMEOUT_MS = 2500

function timeoutSignal(ms: number): AbortSignal {
  const c = new AbortController()
  setTimeout(() => c.abort(), ms)
  return c.signal
}

/** Probes `/health` and classifies why the companion is or is not reachable. */
export async function diagnose(): Promise<Diagnosis> {
  const t0 = performance.now()
  const ms = () => Math.round(performance.now() - t0)
  try {
    const r = await fetch(`${COMPANION_HTTP}/health`, { cache: 'no-store', signal: timeoutSignal(HEALTH_TIMEOUT_MS) })
    const text = await r.text()
    let health: Record<string, unknown> | undefined
    try {
      health = JSON.parse(text)
    } catch {
      health = undefined
    }
    if (!r.ok || !health || health.service !== SERVICE_NAME) {
      return { kind: 'foreign', ms: ms(), detail: `HTTP ${r.status}: ${text.slice(0, 80)}` }
    }
    return { kind: 'ok', ms: ms(), detail: `HTTP ${r.status}`, health }
  } catch (err) {
    const first = `${(err as Error)?.name}: ${(err as Error)?.message}`
    if ((err as Error)?.name === 'AbortError') return { kind: 'timeout', ms: ms(), detail: first }
    try {
      await fetch(`${COMPANION_HTTP}/health`, { mode: 'no-cors', cache: 'no-store', signal: timeoutSignal(HEALTH_TIMEOUT_MS) })
      return { kind: 'cors', ms: ms(), detail: `${first} (no-cors probe: answered)` }
    } catch (err2) {
      const second = `${(err2 as Error)?.name}: ${(err2 as Error)?.message}`
      if ((err2 as Error)?.name === 'AbortError') return { kind: 'timeout', ms: ms(), detail: `${first} / ${second}` }
      return { kind: 'unreachable', ms: ms(), detail: `${first} / no-cors: ${second}` }
    }
  }
}

/** Returns a short Japanese explanation of a diagnosis for the glasses display. */
export function describeForGlasses(d: Diagnosis): string {
  switch (d.kind) {
    case 'ok':
      return 'companion に接続しました'
    case 'unreachable':
      return (
        'companion に接続できません\n' +
        '・FLXR G2 Companion が未起動 / サービス停止中\n' +
        '・または接続拒否 (127.0.0.1:18765)\n' +
        'スマホでアプリを開き「サービス開始」\n' +
        'タップで再試行'
      )
    case 'cors':
      return (
        'companion は応答したが読み取りを拒否\n' +
        '(CORS か Even App の network whitelist)\n' +
        'スマホ画面の診断を確認\nタップで再試行'
      )
    case 'timeout':
      return 'companion が応答しません (timeout)\nアプリを再起動してください\nタップで再試行'
    case 'foreign':
      return `127.0.0.1:${COMPANION_PORT} で別のアプリが応答\nポート競合の可能性\nタップで再試行`
  }
}

/** Transport used to talk to the companion. */
export type TransportKind = 'ws' | 'http'

/** Callbacks invoked by {@link CompanionClient}. */
export interface CompanionHandlers {
  onEvent: (e: CompanionEvent) => void
  onClosed: (why: string) => void
  onLog: (line: string) => void
}

interface Transport {
  readonly kind: TransportKind
  sendPcm(pcm: Uint8Array): void
  control(msg: Record<string, unknown>): void
  close(): void
  droppedBytes: number
}

const WS_OPEN_TIMEOUT_MS = 3000
const WS_MAX_BUFFERED = 64 * 1024

/** Opens the WebSocket transport. */
function openWebSocket(h: CompanionHandlers): Promise<Transport> {
  return new Promise((resolve, reject) => {
    let ws: WebSocket
    try {
      ws = new WebSocket(COMPANION_WS)
    } catch (err) {
      reject(new Error(`WebSocket constructor threw: ${(err as Error)?.message ?? err}`))
      return
    }
    ws.binaryType = 'arraybuffer'
    let opened = false
    const timer = setTimeout(() => {
      if (!opened) {
        ws.close()
        reject(new Error(`WebSocket open timeout (${WS_OPEN_TIMEOUT_MS} ms)`))
      }
    }, WS_OPEN_TIMEOUT_MS)

    const t: Transport = {
      kind: 'ws',
      droppedBytes: 0,
      sendPcm(pcm) {
        if (ws.readyState !== WebSocket.OPEN) return
        if (ws.bufferedAmount > WS_MAX_BUFFERED) {
          t.droppedBytes += pcm.byteLength
          return
        }
        ws.send(pcm)
      },
      control(msg) {
        if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(msg))
      },
      close() {
        ws.onclose = null
        ws.close(1000)
      },
    }

    ws.onopen = () => {
      opened = true
      clearTimeout(timer)
      resolve(t)
    }
    ws.onmessage = ev => {
      if (typeof ev.data !== 'string') return
      try {
        h.onEvent(JSON.parse(ev.data))
      } catch {
        h.onLog(`bad message: ${String(ev.data).slice(0, 80)}`)
      }
    }
    ws.onerror = () => {
      if (!opened) {
        clearTimeout(timer)
        reject(new Error('WebSocket error before open'))
      }
    }
    ws.onclose = ev => {
      clearTimeout(timer)
      if (!opened) reject(new Error(`WebSocket closed before open (code ${ev.code})`))
      else h.onClosed(`ws closed ${ev.code}${ev.reason ? ` ${ev.reason}` : ''}`)
    }
  })
}

/** Opens the HTTP fallback transport (batched PCM POSTs and long-poll events). */
function openHttp(h: CompanionHandlers, startSeq: number): Transport {
  let closed = false
  let pending: Uint8Array[] = []
  let pendingBytes = 0
  let after = startSeq
  let failures = 0
  let chain: Promise<unknown> = Promise.resolve()
  const enqueue = (label: string, op: () => Promise<unknown>) => {
    chain = chain.then(op).catch(err => h.onLog(`${label} failed: ${(err as Error)?.message}`))
  }

  const flush = () => {
    if (closed || pendingBytes === 0) return
    const body = new Uint8Array(pendingBytes)
    let o = 0
    for (const p of pending) {
      body.set(p, o)
      o += p.byteLength
    }
    pending = []
    pendingBytes = 0
    enqueue('POST /api/pcm', () => fetch(`${COMPANION_HTTP}/api/pcm`, { method: 'POST', body }))
  }
  const flushTimer = setInterval(flush, 200)

  const poll = async () => {
    while (!closed) {
      try {
        const r = await fetch(`${COMPANION_HTTP}/api/events?after=${after}&wait=20000`, { cache: 'no-store' })
        const body = (await r.json()) as { next: number; events: CompanionEvent[] }
        failures = 0
        after = body.next
        for (const e of body.events) h.onEvent(e)
      } catch (err) {
        if (closed) return
        if (++failures >= 3) {
          closed = true
          clearInterval(flushTimer)
          h.onClosed(`http long-poll failed: ${(err as Error)?.message}`)
          return
        }
        await new Promise(r => setTimeout(r, 1000))
      }
    }
  }
  void poll()

  const t: Transport = {
    kind: 'http',
    droppedBytes: 0,
    sendPcm(pcm) {
      if (closed) return
      if (pendingBytes > WS_MAX_BUFFERED) {
        t.droppedBytes += pcm.byteLength
        return
      }
      pending.push(new Uint8Array(pcm))
      pendingBytes += pcm.byteLength
    },
    control(msg) {
      if (closed) return
      flush()
      enqueue('POST /api/control', async () => {
        const r = await fetch(`${COMPANION_HTTP}/api/control`, {
          method: 'POST',
          headers: { 'Content-Type': 'text/plain' },
          body: JSON.stringify(msg),
        })
        h.onEvent((await r.json()) as CompanionEvent)
      })
    },
    close() {
      closed = true
      clearInterval(flushTimer)
    },
  }
  return t
}

/** Client for the FLXR G2 Companion app, using WebSocket with an HTTP fallback. */
export class CompanionClient {
  private transport: Transport | null = null
  pcmBytesSent = 0

  constructor(private h: CompanionHandlers) {}

  get kind(): TransportKind | null {
    return this.transport?.kind ?? null
  }

  get droppedBytes(): number {
    return this.transport?.droppedBytes ?? 0
  }

  /** Opens a transport, trying WebSocket first unless `prefer` is `'http'`. */
  async open(prefer: TransportKind | 'auto', health?: Record<string, unknown>): Promise<TransportKind> {
    this.close()
    if (prefer !== 'http') {
      try {
        this.transport = await openWebSocket(this.h)
        return 'ws'
      } catch (err) {
        this.h.onLog(`WebSocket unavailable: ${(err as Error)?.message}`)
        if (prefer === 'ws') throw err
      }
    }
    const seq = typeof health?.seq === 'number' ? health.seq : 0
    this.transport = openHttp(this.h, seq)
    if (health) this.h.onEvent({ type: 'hello', ...(health as unknown as CompanionStatus) })
    return 'http'
  }

  sendPcm(pcm: Uint8Array): void {
    if (!this.transport) return
    this.transport.sendPcm(pcm)
    this.pcmBytesSent += pcm.byteLength
  }

  control(msg: Record<string, unknown>): void {
    this.transport?.control(msg)
  }

  close(): void {
    this.transport?.close()
    this.transport = null
  }
}
