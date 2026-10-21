// Copyright (c) 2026 Takahiro Miyaura
// SPDX-License-Identifier: BSL-1.0
// Distributed under the Boost Software License, Version 1.0.
// See the LICENSE file in the project root.

import { defineConfig } from 'vite'
import app from './app.json'

export default defineConfig({
  server: { host: true, port: 5173 },
  build: { target: 'esnext' },
  define: { __APP_VERSION__: JSON.stringify(app.version) },
})
