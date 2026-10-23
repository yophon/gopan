# 19 · 安卓原生 App 计划

> 结论先行:v2.6 的 Capacitor 壳(`app/`)本质是网页,首屏等网络、手感是 WebView、键盘与返回键别扭、登录要绕身份域名,真机上暴露的问题几乎都来自"网页塞进壳"。这一版推倒,用 Kotlin + Jetpack Compose 重做一个真正的原生客户端,目标是**网页端的全部用户功能在手机上都有原生实现**,后端尽量不动。

## 一、目标与边界

**目标**:一个安卓 App,打开即用,文件列表、上传、下载、预览、分享、回收站、传输助手、设备管理、账户、管理端全部原生;上传下载在后台跑、断网续传、通知栏进度;系统分享菜单直达传输助手;系统"打开方式"能把 gopan 当作文件来源。

**不做**:

- iOS(没有 Mac 与开发者账号,另议)。
- WebDAV 客户端(系统文件 App 和 rclone 已经能挂,App 里只做应用密码管理)。
- MCP / OAuth 授权页(Agent 接入是桌面场景;App 里只做 API Key 与授权列表的查看吊销)。
- 分享访客页(访客不装 App,链接仍走网页)。
- 离线缓存整棵目录树(只缓存列表与缩略图,做到"断网能看上次的列表",不做同步盘)。

**后端改动最小化**:现有 GraphQL 与上传协议原样复用。需要动的只有三处,见第五节。

## 二、功能对照表(网页 → 原生)

| 域 | 网页端 | 原生实现 | 阶段 |
|---|---|---|---|
| 登录 | Yophon ID(OIDC 回调)、密码登录、注册、改密码、登出 | Yophon ID 走 AppAuth(Custom Tabs + PKCE + 自定义 scheme)→ 账号中心移动票据 → gopan 会话;密码登录 / 注册直接调 GraphQL;token 存 EncryptedSharedPreferences;改密码、登出 | P1 |
| 文件列表 | 目录树、面包屑、表格/卡片、排序、多选、长按菜单、文件夹体积 | Compose 列表(卡片/列表两种密度)、面包屑、排序、多选操作条、长按 BottomSheet、下拉刷新、分页(keyset cursor)、Room 缓存上次列表 | P1 |
| 文件操作 | 新建文件夹、重命名、移动(目录选择器)、复制、删除、批量 | 全部,移动/复制用全屏目录选择器 | P1 |
| 上传 | 分片直传(预签名 PUT 并发)、秒传(sha256)、断点续传、队列(暂停/继续/取消/重试)、拖拽/粘贴 | WorkManager 前台服务:选文件(SAF 多选)/ 相册 / 相机 / 系统分享;本地 sha256 流式计算;`initUpload` 秒传;分片并发 PUT,续传按 `uploadSession` 列出的缺片整片重传;`completeUpload` 轮询 `UPLOAD_PROCESSING`;队列持久化 Room,杀进程重启接着传;通知栏进度与完成 | P2 |
| 下载 | 单文件直连、文件夹/多选 zip 打包(`/pack`) | 单文件:OkHttp + Range 断点,进通知栏,存到 Downloads(MediaStore)或用户选的目录(SAF);文件夹:客户端展开逐个下载;打包:带 Bearer 直接 `GET /pack` 流式下载 zip(≤ 2 GiB,不能续传);"用其他应用打开"下到缓存后交给选择器 | P2 |
| 预览 | 图片(缩略图 + 大图缩放 + 左右翻页)、视频/音频流式、PDF、文本/代码、Office 转 PDF、派生物状态轮询 | 图片:Coil + 手势缩放 + 翻页器;视频/音频:Media3 ExoPlayer(流式,后台音频);PDF:`NATIVE_PDF` / `PDF` 派生用 PdfRenderer;文本:Monospace 滚动视图(大文件分段读);Office:`requestPreview` 后轮询派生 PDF;状态 UNAVAILABLE 给"系统打开" | P3 |
| 搜索 | 按名搜索、分页 | 顶栏搜索,结果即列表,同一套操作 | P1 |
| 回收站 | 列表、剩余天数、还原、彻删、清空 | 全部 | P1 |
| 分享 | 创建(密码、有效期)、列表、吊销、复制链接、访问统计(次数 + 明细) | 全部;创建后直接拉系统分享面板发链接;统计用 LazyColumn | P3 |
| 传输助手 | 文本/文件、乐观上屏、粘贴/拖拽、进度卡、翻历史、搜索定位、删除、来源标签、多图网格 | 原生聊天页:文本/文件、上传进度卡(复用上传引擎)、翻历史(before/after)、搜索定位、删除、来源标签、多图网格;轮询 2 秒(前台时),后台不轮询;系统分享 SEND / SEND_MULTIPLE 直达此页 | P2 |
| 登录设备 | 会话列表(当前设备、最后活跃、IP/UA)、逐个吊销 | 全部 | P3 |
| 账户 | 用量条、配额、改密码、应用密码(WebDAV)生成/吊销、Agent API Key 与 OAuth 授权列表/吊销、访问目录限定 | 全部查看与吊销;创建 API Key 保留(明文只显示一次 + 一键复制) | P3 |
| 管理端 | 建号、配额、禁用、重置密码、概览、失败任务重排 | 全部,仅管理员可见 | P4 |
| 系统集成 | PWA 安装、share_target | 系统分享接收;DocumentsProvider(文件 App 里能浏览 gopan、第三方"打开文件"能选 gopan 里的文件)—— 这是网页做不到、原生最值的一项 | P4 |
| 其他 | 深色模式、手机/平板断点 | Material 3 动态取色、深色模式、平板双栏(列表 + 预览) | P3 |

## 三、技术选型

| 项 | 选择 | 理由 |
|---|---|---|
| 语言 / UI | Kotlin 2.x,Jetpack Compose + Material 3 | 原生手感,单 Activity 导航 |
| 架构 | 单 Activity,Navigation Compose;MVVM + Repository;Hilt 注入;Kotlin Coroutines/Flow | 标准做法,可测 |
| GraphQL | Apollo Kotlin 4,直接从 `server/graph/schema.graphqls` 生成,operations 文件放 App 内 | 类型安全,与网页端同一份 schema,CI 能查漂移 |
| 网络 | OkHttp(Apollo 底层同一个客户端),持久化 CookieJar 只存 `gopan_rt`(path `/query`) | `refresh` 走 cookie,与网页端协议一致,后端不改 |
| 认证 | AppAuth-Android(Custom Tabs + PKCE)对接 Yophon ID,自定义 scheme `xyz.yophon.gopan:/oauthredirect` | 账号中心已经为原生客户端设计了移动票据,Yo Entrance 正在用 |
| 后台任务 | WorkManager(前台服务 + 通知),上传/下载各一个 Worker 类型,Room 存队列 | 杀进程、重启、断网都能续 |
| 图片 | Coil 3(OkHttp 共享,带认证) | 缩略图 / 大图 / 手势缩放 |
| 音视频 | Media3 ExoPlayer | 流式、Range、后台播放 |
| PDF | AndroidX PdfRenderer(API 24+) | 零依赖 |
| 存储 | Room(列表缓存、上传下载队列)、DataStore(偏好)、EncryptedSharedPreferences(refresh cookie) | |
| 最低版本 | minSdk 26(Android 8),targetSdk 36 | 前台服务与通知渠道从 26 起规范 |
| 构建 | AGP 8.x,Gradle 8.14,JDK 21(`~/.tools/jdk21`),版本目录 `gradle/libs.versions.toml` | 本机已有 |
| 测试 | 单元:JUnit5 + Turbine + MockWebServer(协议层:上传分片、续传、秒传、轮询);UI:Compose 测试;端到端:模拟器 + 本机实例(与 v2.6 的流程相同,`adb` 驱动) | |
| 分发 | release 签名(沿用 `releases/gopan/gopan-release.jks`),GitHub Releases;App 内"检查更新"读 Releases API | |

## 四、登录协议(原生)

账号中心(yophon-id)为原生客户端设计的链路,Yo Entrance 已在用:

1. App 注册为账号中心的原生客户端:`{"id":"gopan-android","redirectUris":["xyz.yophon.gopan:/oauthredirect"],"delegates":["gopan"]}`(账号中心 `data/clients.json`,生产上加一条,不动已有项)。
2. App 用 AppAuth 发起授权码 + PKCE,Custom Tabs 打开 `https://id.979687.xyz` 登录,回调回自定义 scheme,换到账号中心的 access token(只存内存)。
3. `POST https://id.979687.xyz/api/mobile/ticket` `{client_id:"gopan", challenge:S256(verifier)}` → 一次性票据。
4. `POST https://drive.979687.xyz/api/auth/id/mobile`(form:`ticket`、`verifier`,`redirect=manual`)→ 303,`Set-Cookie: gopan_rt=…`。App 把它存进 CookieJar(加密持久化)。
5. 之后与网页端完全一样:`mutation refresh` 带 cookie 拿 access token,业务请求 Bearer;401 → 单飞 refresh → 重放;refresh 失败 → 回登录页。账号中心撤销设备 → gopan 侧 `refresh` 失败,自动登出。
6. 密码登录 / 注册:直接 `login` / `register` mutation,响应的 `Set-Cookie` 同样进 CookieJar。

不需要后端新接口。可选优化(P1 顺手):`/api/auth/id/mobile` 收到 `Accept: application/json` 时直接返回 `{accessToken, user}` 而不是 303,省一次 refresh。

## 五、后端与仓库改动

| 改动 | 说明 |
|---|---|
| 账号中心注册 `gopan-android` 客户端 | 生产 `clients.json` 加一条,`bun run` 热加载或重启;本地开发用 dev-fixture |
| `/api/auth/id/mobile` JSON 模式(可选) | 见上 |
| `node.downloadUrl` 的预签名 15 分钟 | 下载 Worker 过期重取,不改后端 |
| `requestPreview` / 派生物状态 | 原样 |
| 删除 `app/`(Capacitor 壳) | 原生工程落在 `android/`;v2.6.0/v2.6.1 的 APK 留在 Releases 不删,doc/18 移到 `doc/archive/2026-10-14/`,新 doc/18 写原生 |
| CI | 加 `android` job:`./gradlew lint testDebugUnitTest assembleDebug`;Apollo schema 漂移检查(schema.graphqls 变了 App 必须重新生成) |

## 六、工程结构

```
android/
  settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml
  app/                         Application、单 Activity、导航图、Hilt 根
  core/network                 Apollo + OkHttp + CookieJar + 认证拦截器 + refresh 单飞
  core/auth                    AppAuth 流程、票据交换、会话状态
  core/data                    Repository(nodes / chat / shares / sessions / account / admin),Room
  core/transfer                上传引擎(hash、initUpload、分片 PUT、complete 轮询、续传)、下载引擎、WorkManager Worker、通知
  core/ui                      主题、通用组件(文件图标、面包屑、BottomSheet、空态)
  feature/login
  feature/drive                列表、搜索、回收站、移动/复制选择器
  feature/preview              图片 / 视频 / 音频 / PDF / 文本 / Office
  feature/chat                 传输助手
  feature/shares
  feature/account              用量、设备、应用密码、Agent 凭据、改密码
  feature/admin
  feature/provider             DocumentsProvider、分享接收
```

GraphQL operations 按 feature 放 `feature/*/src/main/graphql/`,共用 schema 指向 `../../server/graph/schema.graphqls`(Gradle 任务拷贝,不手工同步)。

## 七、里程碑

每个阶段独立可装、独立验收,验收都在真机(你连着的华为 AAK-AN00,Android 16)加模拟器(API 36)上跑。

### P1 · 能用(登录 + 文件管理)

- 工程骨架、主题、导航、Apollo 生成、认证层(Yophon ID + 密码 + refresh 单飞 + 自动登出)。
- 文件列表(分页、排序、卡片/列表)、面包屑、搜索、新建文件夹、重命名、移动、复制、删除、多选、回收站(还原/彻删/清空)。
- 列表 Room 缓存:断网打开能看到上次的目录。
- 验收:真机 Yophon ID 登录 ≤ 3 次点击完成;10 万节点的目录滚动不掉帧(keyset 分页);断网重开列表仍在;所有操作与网页端互通(网页改名 App 下拉即见)。

### P2 · 传得稳(上传 / 下载 / 传输助手)

- 上传引擎:SAF 多选、相机拍照、系统分享;sha256 流式;秒传;分片并发 PUT(默认 3 片,Wi-Fi/流量可配);`uploadSession` 缺片续传;`completeUpload` 轮询;Room 队列;前台服务与通知;队列页(暂停/继续/取消/重试/清理)。
- 下载引擎:单文件 Range 续传、打包 zip、保存位置选择、通知栏、"用其他应用打开"。
- 传输助手:全部网页功能(见对照表),系统分享 SEND/SEND_MULTIPLE 直达。
- 验收:1 GB 文件在流量/Wi-Fi 切换、锁屏 10 分钟、杀进程重开三种情况下都传完且 sha 一致;同文件二次上传秒传;相册分享 20 张图一次进传输助手并以网格显示;下载 500 MB 中途断网恢复后续传完成。

### P3 · 看得全(预览 / 分享 / 账户 / 设备)

- 预览:图片翻页缩放、视频音频(含后台播放与通知控制)、PDF、文本、Office 派生 PDF、状态轮询与"系统打开"兜底。
- 分享:创建(密码/有效期)、列表、吊销、复制与系统分享链接、访问统计。
- 账户:用量、改密码、应用密码、Agent API Key / OAuth 授权列表与吊销、访问目录限定查看。
- 登录设备:列表与吊销。
- 深色模式、动态取色、平板双栏。
- 验收:网页端能预览的类型 App 都能;分享链接从 App 发到微信能打开访客页;从 App 吊销当前以外的设备,那台设备下次刷新被登出。

### P4 · 像系统的一部分(管理端 / DocumentsProvider / 发布)

- 管理端全部功能。
- DocumentsProvider:系统文件 App 与第三方"打开文件"面板能浏览并读取 gopan 文件(按需下载),写入落到上传队列。
- App 内检查更新(GitHub Releases)、崩溃日志(本地导出,不接第三方)。
- 删除 `app/`,文档归档,README 与 doc/08/11/18 改写,CI 加 android job,打 v3.0.0,Releases 发正式包。
- 验收:微信"发送文件"面板里能选 gopan 文件;管理员在 App 里建号并设配额,网页端立刻一致;CI 绿。

## 八、风险与对策

- **Yophon ID 原生客户端注册**要改生产账号中心配置,一次性动作,先在 dev-fixture 验证流程再上线。
- **预签名 URL 与 API 同域名**:生产里预签名地址是站点域名下的 `/gopan/…`(反代到 MinIO),上传 PUT 与下载 GET 都不经过 Go;App 用不带 CookieJar、不加 Bearer 的独立 OkHttp 客户端去碰它(`gopan_rt` 的 Path 是 `/query`,本来也带不过去),证书用系统信任链即可。
- **大文件 sha256**在手机上是 CPU 热点:流式读 + 协程 Default 调度器,1 GB 约 10 秒级,进度条显示"计算校验值";秒传命中时省掉整段上传,值得。
- **后台限制**(国产 ROM 杀后台):上传走前台服务 + 通知,且在设置页提示"允许后台运行";杀掉后 WorkManager 重启续传,靠服务端 `uploadSession` 的缺片对账,不靠本地状态。
- **Apollo 生成与 schema 漂移**:CI 里 App 用仓库内 schema 生成,schema 一改 App 编译即失败,比运行时发现强。
- **工作量**:这是四个阶段的活,比 v2.5/v2.6 加起来都大。每阶段结束发一个可装的内测包到 Releases(预发布标记),你用真机反馈,下一阶段再定细节。

## 九、从哪开始

P1 先做,顺序:工程骨架 → 认证层(先把 Yophon ID 原生登录在真机上跑通,这是最大的外部依赖)→ 文件列表 → 文件操作 → 回收站/搜索 → 内测包。

## 十、进度

| 阶段 | 状态 | 验证 |
|---|---|---|
| P1 | 完成(3.0.0-p1) | 单测;本地全链路 E2E `android/tools/e2e/p1.mjs` 12 个场景(Yophon ID 原生登录、撤销设备即时登出、分页与缩略图、增删改与回收站、搜索定位、离线缓存、深色主题、退出);release 包连生产的授权请求冒烟 |
| P2 | 完成(3.0.0-p2) | 单测 187 个(上传 / 下载协议的状态机用内存版服务端与对象存储测);`p2.mjs` 15 个场景:分享单图 / 文本进草稿 / 发文本与收消息 / 长文本转 txt / 多图网格 / 大文件杀进程 + 断网后按服务端已有分片续传且 sha 一致 / 秒传 / 下载断网续传 / 文件夹与打包下载 / 打开 / 删除与「文件已不存在」/ 搜索定位 / 「文件」应用多选 20 张真实分享(SEND_MULTIPLE)/ 锁屏并强制 Doze 时上传照常传完 |
| P3 | 完成(3.0.0-p3) | 单测 248 个(预览的页面状态机、分段读文本、PDF 渲染尺寸、分享 / 设备 / 应用密码 / Agent 的 ViewModel、UA 归类);`p3.mjs` 18 个场景:图片缩放翻页与沉浸 / 视频流式播放 / 音频后台播放与媒体通知 / PDF 逐页与单页放大 / UTF-8、GBK 与 700 KB 分段文本 / Office 转换失败与重试 / 不支持类型直接下载 / 传输助手与搜索结果进预览 / 带密码分享(访客错密码被拒)与系统分享面板 / 访问记录与取消后失效 / 预览里分享 / 吊销另一台设备后它 refresh 被拒 / WebDAV 应用密码与 API Key 生效与吊销 / 动态取色开关 / 平板侧边导航与双栏预览 |
| P4 | 完成(3.0.0) | 单测 267 个(管理端 ViewModel、更新的版本比较与 SHA-256 校验、崩溃日志脱敏与轮换、联网后自动刷新);`p4.mjs` 8 个场景:管理端建号 / 配额 / 禁用启用 / 重置密码与服务端一致 / 失败任务重排 / 检查更新到系统安装器 / 崩溃日志与导出 / 开源许可 / 「文件」App 读写 gopan(sha 一致)/ 断网恢复自动刷新;p1–p4 在 R8 包(releaseE2e)上全部重跑通过;正式签名包冒烟 |

P2 的验收按本地栈的条件缩放:对象存储在服务器上、经 SSH 隧道(上行约 0.5–2 MiB/s),所以大文件用 64 MiB、分片 5 MiB,
覆盖的是同样的路径(杀进程、断网、Doze、秒传、Range 续传);1 GB 与真实网络切换留给真机内测。

P3 的验收在本地栈上做:本地栈配了 Office 转换但转不出来,E2E 走的是「转换失败 → 重试」这条路,没开转换(UNAVAILABLE)由单测覆盖,
真实 docx 的转换效果留给生产内测;「发到微信」用系统分享面板验证(模拟器上没有微信),访客页由浏览器打开。

P4 与计划的出入:App 内检查更新不读 GitHub Releases(仓库私有,匿名请求 404),改由站点发布:服务端 `/api/app/android/latest`
读发版目录里的 `latest.json`,安装包同站下载(doc/09「安卓安装包发布」),GitHub Releases 只做维护者存档。
「微信发送文件面板里能选 gopan」已在华为 AAK-AN00(Android 16)真机上验过:微信「文件」会唤起系统文件选择器,里面能浏览 gopan 云端的文件。

P2 期间顺带修的服务端问题:

- 传输助手里的文件被彻删(手动、清空回收站、30 天过期清理)会撞 `chat_messages` 的 CHECK 约束、整个事务失败;
  迁移 00020 去掉了这条约束,消息保留并显示「文件已不存在」。
- refresh 失败时一律清掉登录 cookie:数据库或账号中心抖一下,所有网页和 App 用户都被登出;现在只在凭证本身无效时清。

