// Copyright (c) 2026 Takahiro Miyaura
// SPDX-License-Identifier: BSL-1.0
// Distributed under the Boost Software License, Version 1.0.
// See the LICENSE file in the project root.

import { TextContainerUpgrade, type EvenAppBridge } from '@evenrealities/even_hub_sdk'

export const CONTAINER_ID = 1
export const CONTAINER_NAME = 'flxr'

const WIDTH_BUDGET = 300
const DEBOUNCE_MS = 120

function units(ch: string): number {
  return ch.charCodeAt(0) > 0xff ? 2 : 1
}

/** Clips text to the display budget, keeping the tail. */
export function fitTail(text: string, budget = WIDTH_BUDGET): string {
  let used = 0
  let i = text.length
  while (i > 0) {
    const u = units(text[i - 1])
    if (used + u > budget - 2) break
    used += u
    i--
  }
  return i > 0 ? `…${text.slice(i)}` : text
}

/** Full-screen text output on the glasses with debounced, serialized updates. */
export class GlassesDisplay {
  private timer: number | null = null
  private lastRendered = ''
  private target = ''
  private inFlight = false

  constructor(private bridge: EvenAppBridge) {}

  /** Requests that `text` be shown on the glasses. */
  show(text: string): void {
    if (text === this.target) return
    this.target = text
    this.schedule()
  }

  private fit(text: string): string {
    return fitTail(text) || ' '
  }

  private schedule(): void {
    if (this.timer !== null) return
    this.timer = window.setTimeout(() => {
      this.timer = null
      void this.render()
    }, DEBOUNCE_MS)
  }

  private async render(): Promise<void> {
    if (this.inFlight) {
      this.schedule()
      return
    }
    const content = this.fit(this.target)
    if (content === this.lastRendered) return
    this.inFlight = true
    try {
      await this.bridge.textContainerUpgrade(
        new TextContainerUpgrade({ containerID: CONTAINER_ID, containerName: CONTAINER_NAME, content }),
      )
      this.lastRendered = content
    } catch (err) {
      console.error('textContainerUpgrade failed:', err)
    } finally {
      this.inFlight = false
      if (this.fit(this.target) !== this.lastRendered) this.schedule()
    }
  }
}
