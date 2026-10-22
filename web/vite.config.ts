import { fileURLToPath, URL } from 'node:url'

import { defineConfig, searchForWorkspaceRoot } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    // 发布页 /app 读仓库根目录的 CHANGELOG.md(?raw 导入),开发服务器默认只放行 web/ 里的文件
    fs: {
      allow: [searchForWorkspaceRoot(process.cwd()), fileURLToPath(new URL('../CHANGELOG.md', import.meta.url))],
    },
    proxy: {
      // 安卓安装包与检查更新(发布页用)
      '/api/app': {
        target: 'http://127.0.0.1:8080',
      },
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
      // Markdown 渲染(doc/23 五.2)
      '/md/': {
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
