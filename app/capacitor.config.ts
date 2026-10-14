import type { CapacitorConfig } from '@capacitor/cli'

/**
 * gopan 安卓壳的 Capacitor 配置。
 *
 * 远程模式:WebView 直接加载线上站点(server.url),不把前端打进 APK。
 * 好处是登录态(httpOnly refresh cookie)、预签名直传、CSP 全部沿用网页端那一套,
 * 不需要跨域 cookie;前端更新也不用发新版 App。代价是首屏依赖网络——云盘离线本来也没内容。
 *
 * 壳只做三件事:系统分享接收(ShareReceiver 原生插件)、桌面图标与启动画面、返回键。
 * 构建时用 GOPAN_APP_URL 指向别的实例(自部署用户改这里或传环境变量)。
 */
const url = process.env.GOPAN_APP_URL ?? 'https://drive.979687.xyz'

const config: CapacitorConfig = {
  appId: 'xyz.yophon.gopan',
  appName: 'gopan',
  webDir: 'www',
  server: {
    url,
    cleartext: url.startsWith('http://'),
  },
  android: {
    // 服务端据此识别 App WebView,放行 Capacitor 注入的 bridge 脚本(见 httpx.WithSecurityHeaders)
    appendUserAgent: 'GopanApp/2.6.0',
    allowMixedContent: false,
  },
  plugins: {
    SplashScreen: {
      launchAutoHide: true,
      launchShowDuration: 800,
      backgroundColor: '#ffffff',
    },
  },
}

export default config
