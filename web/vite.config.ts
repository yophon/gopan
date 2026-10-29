import { fileURLToPath, URL } from 'node:url'

import { defineConfig, searchForWorkspaceRoot } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

export default defineConfig({
  plugins: [
    vue(),
    // Element Plus 按需:模板里用到哪个组件 / 指令(v-loading)才打进包。以前 app.use(ElementPlus) 把整个库塞进首屏。
    // 样式仍然整份引入(main.ts):theme.css 靠先后顺序覆盖组件级变量,按组件拆开的样式会晚到、盖掉主题
    Components({
      // 只管 Element;自己的组件照旧显式 import,不做自动注册
      dirs: [],
      resolvers: [ElementPlusResolver({ importStyle: false })],
      dts: 'src/components.d.ts',
    }),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
      // 助手的内核 tau:编译好放在 src/vendor/tau(doc/33 五,scripts/vendor-tau.mjs)
      '@yophon/tau-kernel': fileURLToPath(new URL('./src/vendor/tau/kernel/index.js', import.meta.url)),
      '@yophon/tau-ext-mcp-http': fileURLToPath(new URL('./src/vendor/tau/ext-mcp-http/index.js', import.meta.url)),
      '@yophon/tau-ext-provider-anthropic': fileURLToPath(new URL('./src/vendor/tau/ext-provider-anthropic/index.js', import.meta.url)),
      '@yophon/tau-ext-provider-google': fileURLToPath(new URL('./src/vendor/tau/ext-provider-google/index.js', import.meta.url)),
      '@yophon/tau-ext-provider-openai-responses': fileURLToPath(new URL('./src/vendor/tau/ext-provider-openai-responses/index.js', import.meta.url)),
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
      // 转成 mp4 的视频、歌词(doc/31)
      '/remux/': {
        target: 'http://127.0.0.1:8080',
      },
      '/lrc/': {
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
      // 助手的模型转发(doc/33)
      '/api/ai': {
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
