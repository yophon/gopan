# 18 · 安卓 App

> `app/` 是 gopan 的安卓壳:Capacitor 8,远程模式加载线上站点,原生只补一件网页做不到的事——接收系统分享菜单里的文字和文件,直接进传输助手。v2.6.0 首发。

## 设计

**远程模式,不把前端打进 APK。** `capacitor.config.ts` 的 `server.url` 指向线上站点(默认 `https://drive.979687.xyz`,自部署改 `GOPAN_APP_URL` 环境变量后重新 `cap sync`)。WebView 直接加载网页端,登录态(httpOnly refresh cookie)、预签名直传、CSP、PWA 的全部逻辑原样复用,不需要跨域 cookie,前端更新也不用发新版 App。代价是首屏依赖网络,云盘离线本来也没内容。

**壳只做三件事:**

1. **系统分享接收。** `AndroidManifest.xml` 声明 `SEND` / `SEND_MULTIPLE`(`text/plain` 与 `*/*`),`MainActivity` 把 intent 交给原生插件 `ShareReceiverPlugin`:文本直接缓存;文件先从 content URI 拷进 `cacheDir`。网页端 `src/native/shareReceiver.ts` 在启动时与 `shareReceived` 事件时调 `getPending()`,文件按 4MB 一片 `readChunk()`(base64)拼成 `File`,`release()` 删临时文件,然后 `push` 进 `stores/incomingShare` 并切到 `/chat`——和 PWA 的 POST share_target 走同一个入口,ChatView 不区分来源。不整段 base64 过桥是为了一段视频不会变成几百 MB 的字符串。
2. **桌面图标与启动画面。** `app/gen_icons.py` 从 `web/public/icons/icon-512.png` 派生 legacy 与 adaptive 前景图,PWA 图标是唯一的设计源。
3. **返回键。** `BridgeActivity` 默认行为:WebView 能后退就后退。

**服务端配合一处:** Capacitor 远程模式会往 HTML 注入一段内联 bridge 脚本,被 `script-src 'self'` 挡住插件就全哑。壳的 UA 带 `GopanApp/<版本>`(`appendUserAgent`),`httpx.WithSecurityHeaders` 只对这种 UA 的响应把 `script-src` 加上 `'unsafe-inline'`;普通浏览器的 CSP 不变。UA 可伪造,但伪造者只能放宽自己那次响应的策略。

**与 PWA 的分工:** PWA 也能装到桌面,也能接收系统分享(POST share_target + Service Worker)。App 多出来的是:分享菜单里更稳定地出现、后续可以加后台上传与通知。日常用 PWA 够,想要"像个 App"就装 APK。

## 目录

```
app/
  package.json            Capacitor 8.5.3(core / cli / android)+ typescript
  capacitor.config.ts     appId xyz.yophon.gopan,server.url,appendUserAgent
  www/index.html          占位(远程模式不用本地资源)
  gen_icons.py            图标派生
  android/                cap add android 生成,已定制:
    app/src/main/AndroidManifest.xml          分享 intent-filter
    app/src/main/java/xyz/yophon/gopan/MainActivity.java
    app/src/main/java/xyz/yophon/gopan/ShareReceiverPlugin.java
    app/build.gradle                          versionCode/Name、签名配置
    keystore.properties                       gitignore,发布签名密码
```

## 构建

依赖:Android SDK(platform 36、build-tools 36)、JDK 21(Capacitor 8 要求 Java 21;Gradle 8.14 不支持 JDK 25,Android Studio 自带的 JBR 不能用)、pnpm。本机:SDK 在 `~/.tools/android-sdk`,JDK 21 在 `~/.tools/jdk21`。

```bash
cd app && pnpm install
export JAVA_HOME=~/.tools/jdk21/jdk-21.0.12.1+1 ANDROID_HOME=~/.tools/android-sdk
npx cap sync android                      # 写 android/app/src/main/assets/capacitor.config.json
cd android && ./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease                 # 需要 android/keystore.properties,否则用 debug 签名
```

版本号在 `app/android/app/build.gradle`(`versionCode` / `versionName`)与 `capacitor.config.ts` 的 `appendUserAgent` 两处,一起改。

**发布签名**在仓库外:仓库外发布目录里的 `gopan-release.jks` + `keystore.properties`,复制到 `app/android/keystore.properties` 即可出 release 包。密钥丢了已装用户就没法升级,务必备份。

**身份服务域名要进白名单。** Yophon ID 登录会把 WebView 带到 `id.979687.xyz`,不在 `server.allowNavigation` 里 Capacitor 会丢给系统浏览器,回调落在浏览器、App 拿不到登录态(2.6.0 的问题,2.6.1 修)。自部署改 `GOPAN_APP_ALLOW_NAV`(逗号分隔 host)。

**指向别的实例**(自部署 / 模拟器打本机):`GOPAN_APP_URL=http://10.0.2.2:8090 npx cap sync android` 再打包。http 实例要求服务端 `GOPAN_DEV_MODE=true`(cookie 不带 Secure)。

## 验证

`scripts/` 里没有放 App 探针,因为它依赖本机模拟器;流程记在这里,2026-10-14 在 API 36 x86_64 模拟器(AVD BeyondPlusSmall,`wm size 390x844`)上跑通:

1. 本机起一个 gopan 实例(`GOPAN_LISTEN=127.0.0.1:8090`,DB/MinIO 走 SSH 隧道到服务器上的临时容器,`GOPAN_S3_PUBLIC_ENDPOINT=10.0.2.2:9009`),打一个 `GOPAN_APP_URL=http://10.0.2.2:8090` 的 debug 包装进模拟器。
2. `adb forward tcp:9333 localabstract:webview_devtools_remote_<pid>` 拿到 WebView 的 CDP,用脚本登录。
3. `adb shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "..." -n xyz.yophon.gopan/.MainActivity` → App 切到 `/chat`,草稿里出现文字。
4. `run-as xyz.yophon.gopan` 把一张 png 放进 App 的 `files/`,`am start -a SEND -t image/png --eu android.intent.extra.STREAM file:///data/data/xyz.yophon.gopan/files/x.png` → 聊天里出现上传卡,完成后变成图片消息,服务端 `chatMessages` 能查到。
5. 顺手暴露并修掉两个网页端的问题:`crypto.randomUUID` 在 http 非安全上下文不存在,上传入口直接抛错(`utils/id.ts` 兜底);手机上传输助手页高度直接取 `visualViewport.height`,没减掉顶栏,输入栏整段在屏幕外(改为"可视区底边 - 页面顶边")。

## 已知限制

- 只有安卓。iOS 没有系统分享到 PWA 的路,原生壳需要 Mac 与开发者账号,暂不做。
- 远程模式下首屏取决于网络;没有离线壳页。
- 文件分享先整个拷进 `cacheDir` 再分片过桥,超大文件(几 GB)会占一份临时空间,传完即删。
- 没有后台上传:分享一个大文件后把 App 切到后台太久,WebView 可能被回收,上传中断(与 PWA 相同,断点续传记录仍在)。
