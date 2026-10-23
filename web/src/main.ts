import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { VueQueryPlugin } from '@tanstack/vue-query'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import './styles/theme.css'
import './styles/responsive.css'

import './composables/themeMode'
import App from './App.vue'
import router from './router'

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(VueQueryPlugin, {
  queryClientConfig: {
    defaultOptions: {
      queries: {
        // 401 的刷新重放已在 api/client 处理,这里不再叠加重试
        retry: false,
        refetchOnWindowFocus: false,
      },
    },
  },
})

app.mount('#app')

// PWA:只在生产环境注册 service worker(见 src/pwa/register.ts)
void import('./pwa/register').then((m) => m.registerPwa())
