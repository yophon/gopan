import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    proxy: {
      '/query': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
      '/pack': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
      // 压缩包条目、字幕、直链、图床上传(doc/21、doc/22)
      '/arc': {
        target: 'http://127.0.0.1:8080',
      },
      '/sub': {
        target: 'http://127.0.0.1:8080',
      },
      '/x/': {
        target: 'http://127.0.0.1:8080',
      },
      '/api/img': {
        target: 'http://127.0.0.1:8080',
      },
      '/mcp': {
        target: 'http://127.0.0.1:8080',
      },
      '^/oauth/(authorize|token|register)': {
        target: 'http://127.0.0.1:8080',
      },
      '/.well-known': {
        target: 'http://127.0.0.1:8080',
      },
    },
  },
})
