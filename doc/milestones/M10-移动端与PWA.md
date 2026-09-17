# M10 · 移动端适配与 PWA(v2.3.0)

> 交付时间:2026-09-17。代号 M10,来自 `doc/13-v2规划.md:85-91` 里被一砍再砍的「体验收尾」——
> 那两条(PWA、移动端手势)这次连同响应式布局一起落地,`doc/07` 里挂着的"后续体验候选"相应划掉。

## 一、交付了什么

| 部分 | 内容 |
| --- | --- |
| 响应式地基 | 两个断点(767 / 1023)、全局 `responsive.css`(安全区、`100dvh`、Element Plus 窄屏钳宽、触屏目标)、`useBreakpoints()` 单例 |
| 外壳 | 侧栏内容抽成 `SideNav.vue`,桌面 `el-aside` 与手机 `el-drawer` 共用一份;手机加顶栏(汉堡) |
| 文件列表 | 手机用卡片列表(`NodeCardList`),支持列表/网格切换;桌面继续 `el-table` |
| 触屏操作 | 长按出底部操作菜单(条目比桌面右键菜单全:重命名/移动/复制都在里面)、多选底栏、预览左右滑动、双指缩放 |
| 其余页面 | 回收站、我的分享、搜索、管理端、分享访客页、聊天页、登录页、上传面板、各弹窗 |
| PWA | manifest + service worker(allowlist,只缓存 `/assets/`)+ 零依赖脚本生成的图标;可"添加到主屏"独立窗口打开 |
| 服务端 | `SPAHandler` 三改:带扩展名的未知路径 404、`.webmanifest` MIME、缓存头 |

**没做**:原生 App / WebView 壳(仍是 `doc/01:49` 的既定范围);离线缓存(云盘离线无意义);`doc/13` 里 M10 的另一条"图片预览左右翻页预加载"仍欠着。

## 二、关键决策

**断点取 767 / 1023。** 767 的由来:桌面表格的最小列宽(44+320+120+180+80 ≈ 744px)在这条线以下必然横向滚动;1023 的由来:内容区宽 = 视口 − 侧栏 220 − 内边距 48,到 1024 才放得下 744px 的表。所以 768–1023 保留表格(只隐藏次要列),不做卡片 —— 宁可平板看到紧凑表格,也不让桌面窄窗突然丢失表格。

**表格不全网替换。** 桌面 `el-table` 一行没改,手机另起 `NodeCardList`。两套渲染的成本靠"逻辑只写一份"压:`nodeDisplay.ts`(图标/大小文案)、`NodeIcon.vue`(缩略图与回落)、`useNodeActions` 相关的 mutation 都留在视图里,模板外壳各写一个。加新操作必须改公用的那一份,不能只改一处模板 —— 这条写进了 `doc/07`。

**触屏菜单必须比桌面右键全。** 右键菜单历史上只有"下载 + 分享",桌面用户靠工具栏按钮补;手机上长按是唯一入口,所以 `NodeActionSheet` 的条目按完整操作集给(打开/下载/分享/重命名/移动/复制/选择/删除),否则手机上会出现"能看不能改名"的残废状态。

**SW 用 allowlist 而不是黑名单。** 预签名 URL 与页面同源(`GOPAN_S3_PUBLIC_ENDPOINT` 就是对外域名),SW 的 scope 天然覆盖上下载。如果按"排除上传路径"来写,迟早漏掉一条;现在只有 `/assets/` 会进缓存,`/query`、`/dav`、`/pack`、`/mcp`、`/oauth/*`、`/s/:token`、`/gopan/*` 在结构上不可能被拦截。上传相关的 bug 因此变成不可能事件,而不是靠记得维护排除表。

**CSP 一行没改。** `default-src 'self'` 已覆盖 manifest,`script-src`/`worker-src 'self'` 覆盖 SW 注册与运行,`connect-src` 覆盖 SW 内的 fetch。结论是在生产模式(`GOPAN_DEV_MODE=false`)下验证过的 —— dev 模式跳过 CSP,在那里测不算数。

## 三、实现要点

- `composables/breakpoints.ts`:`resolveBreakpoint(width)` 是纯函数(vitest 直接测),`useBreakpoints()` 用 detached 的 `effectScope` 建模块级单例 —— 普通单例挂在第一个调用它的组件上,那个组件卸载会把监听一起销毁。
- `composables/longPress.ts`:状态机 `idle → pending → fired|cancelled`。两个坑都踩过:①长按抬手时浏览器补的 click 会落在刚弹出的遮罩上把菜单瞬间关掉(`NodeActionSheet` 开菜单后 350ms 内忽略遮罩点击);②长按只对触屏生效,但轻点必须对鼠标也生效,否则窄窗口下用鼠标点卡片毫无反应。
- `utils/gesture.ts`:缩放/长按判定是纯函数进 vitest;滑动判定用 vueuse 的 `useSwipe`(自己写的 `swipeAxis` 已删,别再长出来)。
- `components/preview/ImageViewer.vue`:pinch 以两指中点为不动点,叠加中点自身位移(既能缩放也能平移);从双指回到单指时重设拖动基准,否则会瞬移。回传 `magnified`(`scale !== 1`)给预览用来让滑动让位 —— **纯平移不算放大**,否则 100% 下拖一下图就再也划不动了。
- 预览:手机上关掉遮罩点击关闭(滑动结束的 click 会误关),导航按钮只留给桌面,头部 56→48px、内边距 76→12px。
- 聊天页用 `visualViewport` 的真实高度顶替页面高度:iOS 上键盘弹起不会缩小布局视口,固定 100% 会把输入框盖在键盘下面。
- `server/internal/httpx/server.go`:`SPAHandler` 对带扩展名的未知路径返回 404 而不是回落 index.html。原来的"任意未知路径回落"会让缺失的 `/sw.js` 返回 HTML,MIME 报错且极难排查。

## 四、验收

- `web`:`pnpm typecheck` / `pnpm test`(16 文件 105~109 用例)/ `pnpm build` 全过。
- `scripts/smoke/browser_probe.mjs`:桌面全流程跑通后切 390×844 + 触摸仿真再验一轮 —— 无横向溢出、侧栏隐藏/顶栏出现、不渲染宽表格、建文件夹后长按出底部菜单(必须含桌面右键没有的重命名/移动/复制)、产物里有 `sw.js` 则断言已注册、`POST /query` 不被 SW 影响。**桌面视口必须显式设置**:headless 默认窗口只有 ~746px,会落进手机断点让断言全部失准。
- 生产模式(`GOPAN_DEV_MODE=false`)实测:`/sw.js` 与 manifest 的 MIME 与 `no-cache`、`/assets/` immutable、未知扩展名 404;浏览器内 SW `activated`、`gopan-assets-v1` 缓存 22 条全为 `/assets/`、`POST /query` 返回 200。
- 后端 `go test ./...` 全绿(本机 `TestMakeOfficePDF` 受免 root Gotenberg 冷启动影响,与本次无关)。

## 五、遗留

**本期明确没做的弱网/后台可靠性**(下轮候选):

1. iOS 切后台时标签页可能被回收,上传任务静默死亡 —— 现在只有 `beforeunload` 守卫,没有 `visibilitychange`/`pagehide` 处理,`File` 句柄也没持久化。
2. 断点续传按 `文件名 + size + lastModified` 匹配(`stores/uploads.ts`),iOS「文件」App 重新选择同一个文件时名字或 mtime 常变,匹配失败 → 续传失效。
3. `doc/13` 里 M10 的另一条:图片预览左右翻页预加载。
4. 真机手测清单还没跑:iOS Safari 主屏(standalone)下的安全区与键盘、Android Chrome 的安装横幅、微信内置浏览器打开分享页。

## 六、部署提示

不需要新服务、不涨常驻内存(SW 跑在客户端),二进制只多约 80KB 图标。`make build` 之后 `server/cmd/gopan/dist/` 会多出 `manifest.webmanifest`、`sw.js`、`icons/`、`favicon.svg`,`go:embed all:dist` 自动带上。升级仍是 `docker compose -f docker-compose.prod.yml up -d --build gopan`;因为 index.html 与 sw.js 都是 `no-cache`,用户下次打开就能拿到新版(已安装的 App 在页面重新可见时最多 1 小时后检查更新)。
