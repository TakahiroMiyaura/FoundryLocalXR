// Copyright (c) 2026 Takahiro Miyaura
// SPDX-License-Identifier: BSL-1.0
// Distributed under the Boost Software License, Version 1.0.
// See the LICENSE file in the project root.

import {
  waitForEvenAppBridge,
  TextContainerProperty,
  CreateStartUpPageContainer,
  OsEventTypeList,
} from '@evenrealities/even_hub_sdk'
import {
  CompanionClient,
  COMPANION_HTTP,
  describeForGlasses,
  diagnose,
  type CompanionEvent,
  type Diagnosis,
  type TransportKind,
} from './companion'
import { CONTAINER_ID, CONTAINER_NAME, GlassesDisplay } from './glasses'
import { logLine, mountUi, setAi, setDiagnostics, setMicLabel, setStatus, setYou } from './ui'

const RETRY_MS = 5000
const PREFER = ((new URLSearchParams(location.search).get('transport') as TransportKind | null) ?? 'auto') as
  | TransportKind
  | 'auto'

let prefer: TransportKind | 'auto' = PREFER
let appReady = false

mountUi(
  {
    onReconnect: () => {
      if (appReady) void connect()
    },
    onToggleMic: () => {
      if (appReady) void toggleMic()
    },
    onAsk: text => {
      if (!appReady || !connected) return logLine('ask ignored: not connected')
      setYou(text)
      you = text
      ai = ''
      client.control({ type: 'ask', text })
    },
    onUseHttp: () => {
      prefer = 'http'
      if (appReady) void connect()
    },
  },
  __APP_VERSION__,
)

const bridge = await waitForEvenAppBridge()
console.log(`[flxr-g2] bridge ready v${__APP_VERSION__} origin=${location.origin}`)

const created = await bridge.createStartUpPageContainer(
  new CreateStartUpPageContainer({
    containerTotalNum: 1,
    textObject: [
      new TextContainerProperty({
        xPosition: 0,
        yPosition: 0,
        width: 576,
        height: 288,
        borderWidth: 0,
        borderColor: 5,
        paddingLength: 4,
        containerID: CONTAINER_ID,
        containerName: CONTAINER_NAME,
        content: 'companion に接続中…',
        isEventCapture: 1,
      }),
    ],
  }),
)
if (created !== 0) logLine(`createStartUpPageContainer failed: ${created}`)

const glasses = new GlassesDisplay(bridge)

let connected = false
let connecting = false
let server: { ready: boolean; listening: boolean; status: string; error: string | null; models: string } | null = null
let lastDiagnosis: Diagnosis | null = null
let micOn = false
let micPaused = false
let you = ''
let ai = ''
let partial = ''
let retryTimer: number | null = null
let connectedAt = 0

const client = new CompanionClient({
  onEvent: handleEvent,
  onClosed: why => {
    logLine(`disconnected: ${why}`)
    connected = false
    void stopMic()
    void connect()
  },
  onLog: logLine,
})

/** Renders the current state on the glasses and the phone status line. */
function render(): void {
  if (!connected) return
  let head: string
  if (!server?.ready) head = `[準備中] ${server?.status ?? ''}`
  else if (micPaused) head = '[一時停止] タップで再開'
  else if (partial) head = '[文字起こし中]'
  else head = '[聞き取り中] 話しかけてください'
  if (server?.error) head += `\n! ${server.error}`
  const body = [you && `あなた: ${you}`, ai && `AI: ${ai}`].filter(Boolean).join('\n')
  glasses.show(body ? `${head}\n${body}` : head)
  setStatus(head.split('\n')[0], server?.error ? 'error' : server?.ready ? 'ok' : 'warn')
}

/** Updates the diagnostics panel on the phone screen. */
function refreshDiagnostics(): void {
  setDiagnostics({
    pageOrigin: location.origin,
    companion: COMPANION_HTTP,
    diagnosis: lastDiagnosis ? `${lastDiagnosis.kind} (${lastDiagnosis.ms} ms)` : '-',
    detail: lastDiagnosis?.detail ?? '-',
    health: lastDiagnosis?.health ?? '-',
    transport: client.kind ?? '(none)',
    connected,
    connectedFor: connected ? `${Math.round((Date.now() - connectedAt) / 1000)} s` : '-',
    mic: micPaused ? 'paused' : micOn ? 'on' : 'off',
    pcmSentKB: (client.pcmBytesSent / 1024).toFixed(1),
    pcmDroppedKB: (client.droppedBytes / 1024).toFixed(1),
    serverStatus: server?.status ?? '-',
    models: server?.models ?? '-',
    userAgent: navigator.userAgent,
  })
}
setInterval(refreshDiagnostics, 1000)

/** Applies a companion event to the local state. */
function handleEvent(e: CompanionEvent): void {
  switch (e.type) {
    case 'hello':
    case 'status':
      server = { ready: e.ready, listening: e.listening, status: e.status, error: e.error, models: e.models }
      if (e.type === 'hello') logLine(`hello: ready=${e.ready} status=${e.status}`)
      void syncMic()
      break
    case 'partial':
      partial = e.text
      break
    case 'transcript':
      you = e.text
      ai = ''
      partial = ''
      setYou(you)
      setAi('')
      logLine(`heard: ${e.text}`)
      break
    case 'reply_delta':
      ai += e.text
      setAi(ai)
      break
    case 'reply':
      ai = e.text
      setAi(ai)
      logLine(`reply: ${e.text}`)
      break
    case 'error':
      logLine(`server error: ${e.message}`)
      break
    default:
      break
  }
  render()
}

/** Diagnoses the companion and opens a transport, scheduling a retry on failure. */
async function connect(): Promise<void> {
  if (connecting) return
  connecting = true
  if (retryTimer !== null) {
    clearTimeout(retryTimer)
    retryTimer = null
  }
  try {
    client.close()
    connected = false
    await stopMic()
    setStatus('companion を診断中…', 'warn')
    lastDiagnosis = await diagnose()
    logLine(`diagnose: ${lastDiagnosis.kind} ${lastDiagnosis.ms}ms ${lastDiagnosis.detail}`)
    refreshDiagnostics()
    if (lastDiagnosis.kind !== 'ok') {
      glasses.show(describeForGlasses(lastDiagnosis))
      setStatus(describeForGlasses(lastDiagnosis).split('\n')[0], 'error')
      scheduleRetry()
      return
    }
    let kind: TransportKind
    try {
      kind = await client.open(prefer, lastDiagnosis.health)
    } catch (err) {
      const msg = `/health は OK だが WebSocket 不可: ${(err as Error)?.message}`
      logLine(msg)
      glasses.show('companion: HTTP は OK、WebSocket 不可\nスマホ画面の診断を確認\nタップで再試行')
      setStatus(msg, 'error')
      scheduleRetry()
      return
    }
    connected = true
    connectedAt = Date.now()
    logLine(`connected via ${kind}`)
    if (kind === 'http') logLine('using HTTP fallback (POST /api/pcm + long-poll)')
    render()
    await syncMic()
  } finally {
    connecting = false
    refreshDiagnostics()
  }
}

/** Schedules a reconnect attempt. */
function scheduleRetry(): void {
  if (retryTimer !== null) return
  retryTimer = window.setTimeout(() => {
    retryTimer = null
    if (!connected) void connect()
  }, RETRY_MS)
}

/** Turns the glasses mic on only while connected, the model is ready and the user has not paused it. */
async function syncMic(): Promise<void> {
  const want = connected && !!server?.ready && !micPaused
  if (want && !micOn) {
    micOn = true
    await bridge.audioControl(true)
    logLine('mic on')
  } else if (!want && micOn) {
    await stopMic()
  }
}

/** Turns the glasses mic off. */
async function stopMic(): Promise<void> {
  if (!micOn) return
  micOn = false
  await bridge.audioControl(false)
  logLine('mic off')
}

/** Pauses or resumes the mic, finalizing the current utterance on pause. */
async function toggleMic(): Promise<void> {
  micPaused = !micPaused
  setMicLabel(micPaused)
  if (micPaused) client.control({ type: 'stop' })
  await syncMic()
  render()
}

let cleanedUp = false
/** Releases the mic, the transport and the event subscription. */
function cleanup(): void {
  if (cleanedUp) return
  cleanedUp = true
  void bridge.audioControl(false)
  client.close()
  unsubscribe()
}

/** Returns the event type of an envelope, treating a missing type as CLICK_EVENT. */
function eventTypeOf(envelope?: { eventType?: OsEventTypeList }): OsEventTypeList | null {
  if (!envelope) return null
  return envelope.eventType ?? OsEventTypeList.CLICK_EVENT
}

const unsubscribe = bridge.onEvenHubEvent(event => {
  const pcm = event.audioEvent?.audioPcm
  if (pcm) {
    if (connected) client.sendPcm(pcm)
    return
  }
  const sysType = eventTypeOf(event.sysEvent)
  const textType = eventTypeOf(event.textEvent)

  if (sysType === OsEventTypeList.DOUBLE_CLICK_EVENT || textType === OsEventTypeList.DOUBLE_CLICK_EVENT) {
    void bridge.shutDownPageContainer(1)
    return
  }
  if (sysType === OsEventTypeList.CLICK_EVENT || textType === OsEventTypeList.CLICK_EVENT) {
    if (!connected) void connect()
    else void toggleMic()
    return
  }
  if (sysType === OsEventTypeList.SYSTEM_EXIT_EVENT || sysType === OsEventTypeList.ABNORMAL_EXIT_EVENT) {
    cleanup()
  }
})

window.addEventListener('beforeunload', cleanup)

appReady = true
void connect()
