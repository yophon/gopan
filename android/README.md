# gopan 安卓原生客户端

Kotlin + Jetpack Compose(Material 3)重写的 gopan 客户端,v3.0.0 起替代 v2.6 的 Capacitor 壳(壳已从仓库删除)。
用法、权限、发布流程见 `doc/18-安卓App.md`,总计划与验收记录见 `doc/19-安卓原生App计划.md`。四个阶段全部完成:
- P1:工程骨架、认证/网络核心、登录(Yophon ID + 密码登录/注册)、文件(列表/网格、排序、分页、多选批量、
  新建/重命名/移动/复制/删除、回收站、搜索)与「我的」(用量、改密码、外观、退出);
- P2:上传 / 下载引擎(后台前台服务、断网与杀进程后续传、秒传、打包下载)、传输列表与传输设置、
  传输助手(网页端全部功能)、系统分享直达传输助手;
- P3:预览(图片缩放翻页、视频、音频后台播放、PDF、文本、Office 派生 PDF)、分享(新建 / 列表 / 访问记录 / 取消)、
  登录设备、WebDAV 应用密码、Agent 接入(API Key / OAuth 授权 / 目录范围 / 操作记录)、配色与深浅、平板侧边导航与双栏预览;
- P4:管理端、系统「文件」App 里的 gopan(DocumentsProvider)、站内检查更新、崩溃日志、开源许可、断网恢复自动刷新、release 开 R8。

## 构建

依赖:JDK 21、Android SDK(platforms 36、build-tools 36)、Gradle 8.14.3(wrapper 自带)。

```bash
export JAVA_HOME="$HOME/.tools/jdk21/jdk-21.0.12.1+1"   # 唯一能用的 JDK:17 不能 target 21,25 Gradle 8.14 不支持
export ANDROID_HOME="$HOME/.tools/android-sdk"
cd android
./gradlew --no-daemon -q :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew --no-daemon -q :app:testDebugUnitTest      # JVM 单测(MockWebServer)
./gradlew --no-daemon -q :app:installDebug           # 有设备/模拟器时直接装
./gradlew --no-daemon -q :app:lintDebug              # CI 同款 lint(0 error 才算过)
./gradlew --no-daemon -q :app:assembleRelease        # R8 压缩 + 混淆,约 5 MB;签名见下
./gradlew --no-daemon -q :app:assembleReleaseE2e     # 同一套 R8,debug 签名 + 测试钩子,给 E2E 用(见「R8」)
```

Android Studio 打开 `android/` 即可(Gradle JDK 选 21)。

## 签名

`android/keystore.properties`(gitignore):

```
storeFile=<仓库外的发布目录>/gopan-release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

存在时 `assembleRelease` 用它签名;不存在时 release 退回 debug 签名(只能本地验证,不能发布)。
applicationId 仍是 `xyz.yophon.gopan`、签名同一把 key,可原地覆盖安装 v2.6 的壳;versionCode 只增不减
(内测包 3.0.0-p1…p3 是 30001…30003,正式版 3.0.0 是 30100)。

## BuildConfig 覆盖

服务端地址等写在 BuildConfig,默认指向生产;本地联调在 `gradle.properties` 或命令行 `-P` 覆盖:

| 属性 | BuildConfig 字段 | 默认值 |
|---|---|---|
| `gopan.baseUrl` | `GOPAN_BASE_URL` | `https://drive.979687.xyz` |
| `gopan.idIssuer` | `YOPHON_ID_ISSUER` | `https://id.979687.xyz` |
| `gopan.idClientId` | `YOPHON_ID_CLIENT_ID` | `gopan-android` |
| `gopan.idRedirect` | `YOPHON_ID_REDIRECT` | `xyz.yophon.gopan:/oauthredirect` |

例:`./gradlew -Pgopan.baseUrl=http://10.0.2.2:8090 :app:installDebug`(模拟器打本机实例)。
debug 与 releaseE2e 包的 `network_security_config` 只放行 `10.0.2.2` / `127.0.0.1` / `localhost` 的明文 HTTP,release 一律 HTTPS。

`BuildConfig.TEST_HOOKS`(debug、releaseE2e 为 true,release 为 false)管三件只给测试用的事:Compose testTag 暴露成
resource-id(uiautomator 据此定位)、Timber 打日志、Yophon ID 允许本机明文 HTTP 的账号中心。不要用 `BuildConfig.DEBUG` 判断这些。

手机上装着 release 签名的 `xyz.yophon.gopan`(v2.6 壳或正式版)时,debug 签名的包装不进去(`INSTALL_FAILED_UPDATE_INCOMPATIBLE`),
不必卸载:`-Pgopan.debugAppIdSuffix=.dev` 让 debug 包以 `xyz.yophon.gopan.dev` 并存安装(`gopan.debugAppIdSuffix` 留空即无后缀)。
两个包都注册了 `xyz.yophon.gopan:/oauthredirect` 的 scheme 时,Yophon ID 授权回跳可能弹出应用选择器,选带后缀的那个即可。

## 目录结构

单模块 `:app`,按包分层:

```
app/src/main/graphql/            GraphQL operations(按域一个文件,字段选择与 web/src/api/operations 一致;
                                 nodes.graphql 的 NodeItem 片段供 Children/SearchNodes/SearchContent/Trash/Node/CreateFolder/RenameNode 共用)
app/src/main/kotlin/xyz/yophon/gopan/
  GopanApp.kt                    @HiltAndroidApp;启动时跑 SessionManager.startBootstrap();
                                 实现 Coil 的 SingletonImageLoader.Factory,缩略图加载复用全局 OkHttpClient
  MainActivity.kt                唯一 Activity:GopanTheme → RootNavHost;接住系统分享(SEND / SEND_MULTIPLE)与通知点按,
                                 转成 core.share 的待处理内容与跳转请求
  MainViewModel.kt               登录态 + 外观偏好(深浅、配色)
  RootNavHost.kt                 按 AuthState 切换:启动页 / 登录图 / MainScaffold;全屏预览(Routes.Preview)也挂在这一层,盖住底部导航
  MainScaffold.kt                底部导航(文件 / 传输助手 / 分享 / 我的;宽 ≥ 600dp 换成侧边导航栏)+ 主 NavHost —— 导航栏与 NavHost 只在这里;
                                 innerPadding 已含系统栏并 consumeWindowInsets,tab 内页面的 TopAppBar/Scaffold
                                 用默认 insets 或零 insets(feature.drive.NestedScreenInsets)都不会双倍留白;键盘弹起时收起底部栏。
                                 传输列表(Routes.Transfers)是这里的顶层路由,不属于哪个 tab
  MainScaffoldViewModel.kt       跳转请求(分享 → 传输助手、通知 → 传输列表);「打开」的下载完成后弹应用选择器;进来时启动 StartupPrefetch
  StartupPrefetch.kt             进主界面后先把手机上存的传输助手最新一页与我的分享读进 ScreenCache,再并行联网预取(每个账号每个进程一次)
  di/AppModule.kt                Hilt 根模块:AppConfig、OkHttp、Apollo、SecureStore、DataStore;
                                 功能线自己的 @Module(core.data.DataModule、feature.login.LoginModule)由 Hilt 自动聚合
  core/AppConfig.kt              服务端/账号中心地址(BuildConfig → 对象,测试可替换)
  core/network/                  SecureStore(加密 KV)、GopanCookieJar、OkHttp/Apollo 工厂、AuthInterceptor、GopanException
  core/auth/                     AuthState/UserSummary、SessionStore、SessionManager、Pkce、TicketExchange、YophonIdLogin
  core/data/                     Node / NodeListing 模型、NodesRepository(接口)+ NodesRepositoryImpl(Apollo;文件夹 LRU 解析面包屑)、
                                 ListingCache;db/:GopanDatabase、cached_listing 表 + RoomListingCache(按 (用户, 目录) 存首页,进目录先显示、断网回退);
                                 ScreenCache(各页首屏的快照,先显示后刷新;带序列化器的键经 SnapshotDisk 落盘);DataModule
  core/data/chat/                ChatMessage 模型、ChatRepository(接口)+ ApolloChatRepository(列表 / 翻历史 / 搜索 / 发送 / 删除 / 文件夹)
  core/data/preview/             PreviewRepository:单节点预览信息(预签名地址现取)、Office 转换触发、文本分段拉取(TextFetcher:Range、UTF-8 / GB18030)、
                                 PDF 下到缓存(cacheDir/preview-pdf,内容寻址,300 MB 封顶)
  core/data/media/               影音(doc/22 二):MediaRepository(连播列表、最近播放、上报 / 清除进度)、PlayProgressReporter(进程级作用域上报,
                                 5 分钟以下的音视频与 3 页以下的 PDF 不发)、MediaUrls(队列占位地址 gopan-node://<id> → 现取的预签名地址,10 分钟内复用)、
                                 NowPlayingStore(后台播放服务写的「正在播放」)、SubtitleAuthInterceptor(只给本站 /sub/ 加 Bearer)、MediaModule
  core/data/shares/              SharesRepository:我的分享、新建、取消、访问明细
  core/data/collect/             FileRequestsRepository:收集链接(doc/20)的属主侧——列表、新建、修改、结束、标已读、收件记录
  core/data/security/            SecurityRepository:登录设备、WebDAV 应用密码、MCP API Key / OAuth 授权 / 目录范围 / 操作记录
  core/data/admin/               AdminRepository:用户列表、概览、建号、配额、禁用、重置密码、失败任务重排
  core/data/archive/             压缩包(doc/21 四):ArchiveRepository(概要、目录分页、提交解压)、ArchiveEntryFetcher(带登录态 GET /arc/{节点}/{序号}
                                 到缓存 cacheDir/archive-entry,解析 JSON 错误)、ArchiveModels(模型、isArchiveName、按扩展名判断预览类型)
  core/data/jobs/                后台任务(doc/21):JobsRepository(我的任务、离线下载开关(本次登录缓存)、提交链接、取消、重试、清除已结束)、
                                 JobsLogic(链接识别、列表口径、进度与状态文案、速度,与网页端 utils/jobs.ts 一致的纯函数)、JobsModule
  core/data/notifications/       站内通知(doc/22 三):NotificationsRepository(列表 / 未读数 / 标已读 / 清空)、UnreadNotifications(角标计数)、NotificationsModule
  core/data/links/               直链(doc/22 四):DirectLinksRepository(开关(本次登录缓存)、我的直链、生成、暂停、吊销;站内路径拼服务端地址)、
                                 LinkFormats(链接 / Markdown / HTML 的写法,纯函数)
  core/data/activity/            动态与撤回(doc/23 二):ActivityRepository(列表与筛选、来源、撤回预览、撤回)、ActivityModule
  core/data/space/               空间分析(doc/23 三):SpaceRepository(总览、文件夹逐层、大文件、很久没动、重复文件、空文件夹、
                                 没人用的分享 / 直链 / 收集链接及吊销)、SpaceModule;清理走 NodesRepository.delete / purgeTrash
  core/data/feeds/               订阅(doc/23 四):FeedsRepository(列表、预览、新建、暂停 / 恢复(带上全部现有设置)、退订、立即检查、单条下载 / 剪藏)
  core/data/clips/               剪藏(doc/23 五):ClipsRepository(列表、按节点查剪藏、clipUrl、标已读、剪藏任务轮询)
  core/data/markdown/            MarkdownUrls:/md/{节点} 渲染页地址(token 现取,快过期先刷新)、MarkdownLinks(WebView 里点链接留在里面 / 交给浏览器 / 拦下)
  core/documents/                系统「文件」App 里的 gopan,见下文「DocumentsProvider」:GopanDocumentsProvider、DocumentCache(按需下载的读缓存)、
                                 PendingDocuments(别的应用写进来的新文件 → 上传队列)、DocumentsModule(登出时清理、登录态变化时刷新根目录)
  core/app/                      UpdateRepository(站点发布的安装包:检查、下载、校验)、CrashLog(未捕获异常写本地日志)
  core/notify/                   系统通知栏提醒(doc/22 决策 6):InboxScheduler(登录且开着开关时排每 15 分钟一次的 InboxWorker)、InboxChecker、
                                 InboxLogic(哪些是新的,纯函数)、InboxPrefs(开关、提醒到哪条;与偏好同一个 DataStore,键以 inbox_ 开头)、InboxNotifier(渠道 inbox / inbox_alert)
  core/prefs/                    UserPreferencesRepository(DataStore:主题、列表视图、排序、传输设置、传输助手草稿)
  core/transfer/                 传输引擎,见下文「传输引擎」:TransferEngine、TransferWorker + TransferScheduler、TransferNotifications、
                                 SourceStore(来源读取、哈希、私有副本)、NetworkMonitor、FileOpener、TransferActions(界面入口);
                                 upload/(UploadApi、PartUploader、UploadRun)、download/(DownloadApi、RangeFetcher、DownloadRun、DownloadPublisher)、
                                 db/(upload_task、download_task 两张队列表)
  core/share/                    IncomingShareStore(系统分享的接收与暂存)、AppNavRequests(外部入口的跳转请求)
  core/ui/transfer/              上传选择器(文件 / 照片和视频 / 拍照)与通知、存储权限的申请
  core/ui/theme|components|format|nav   主题(配色经 LocalPalette 传给嵌套主题,品牌色在 LocalGopanColors)、品牌图形(Brand.kt:文件夹记号与字标、
                                 两片的文件夹图标、文件夹面板)、通用组件(Loading/Error/Empty/Confirm/TextInput/Placeholder、
                                 Zoomable 双指 / 双击缩放)、格式化(含 describeUserAgent,规则与网页端一致)、Routes
  core/ui/SystemActions.kt       复制、系统分享面板发文本、浏览器打开链接
  core/ui/WindowSize.kt          宽度断点:600dp 侧边导航栏、840dp 列表 + 预览双栏
  feature/login/                 LoginScreen(Yophon ID 主入口 + 登录/注册 tab)、LoginViewModel、LoginRules(校验、错误码→文案)、LoginGateway + LoginModule
  feature/drive/                 DriveScreen / DriveViewModel(列表/网格、排序、下拉刷新、自动翻页、多选批量;宽屏时点文件在右侧栏预览)、
                                 NodeActionsViewModel(新建/重命名/移动/复制/删除/分享/预览)、
                                 FolderPickerDialog(全屏目标选择器)、BreadcrumbBar、NodeSheets(详情/操作)、DriveGraph(文件 tab 嵌套图,含回收站与搜索路由)
  feature/trash/                 TrashScreen / TrashViewModel(还原、彻删、清空两次确认)、TrashExpiry(剩余天数)
  feature/search/                SearchScreen / SearchViewModel(「文件名」「内容」两个标签,300ms 防抖、分页;只搜当前标签,换关键词两边作废)、
                                 ContentHits(内容结果行:命中页、片段高亮按码点换算到 UTF-16)
  feature/account/               AccountScreen / AccountViewModel(用量条、回收站与传输列表入口、传输设置、改密码对话框、主题 / 配色 / 列表视图偏好、
                                 管理端入口(仅管理员)、检查更新(打开页面时静默查一次,有新版本标出)、崩溃日志与开源许可入口、退出登录)、
                                 AccountUsage、ChangePasswordForm
  feature/account/security/      登录设备、WebDAV 应用密码、Agent 接入三页(SecurityScreens / SecurityViewModels)与 MCP 权限中文名(McpScopes)
  feature/chat/                  传输助手:ChatScreen / ChatItems(消息、九宫格、上传卡片)/ ChatViewModel(轮询、搜索定位、乐观发送、分享落地)/
                                 ChatLogic(按 id 合并、按天分组、网格、链接识别,纯函数)
  feature/transfers/             传输列表(TransfersScreen / TransfersViewModel:上传、下载两栏)与传输设置对话框;
                                 「后台任务」栏(JobsTab / JobsViewModel:可见且有进行中的任务时每 2 秒轮询,取消 / 重试 / 打开所在位置)
  feature/archive/               压缩包:ArchiveScreen / ArchiveViewModel(逐层浏览、返回键回上一层、分页、长按多选、PENDING 每 2 秒轮询)、
                                 ArchiveEntryViewer(包里的图片 / 文本 / PDF)、Extract(解压提交与「解压到…」选择器,预览页 / 浏览页 / 长按菜单共用)、
                                 ArchiveRules(面包屑、标记文案、点按动作等纯函数)、ArchiveFiles(FileProvider 打开 / 分享);预览页的概要卡片在 feature/preview/ArchiveSummaryPage
  feature/fetch/                 离线下载:文件页「+」→「离线下载」对话框与传输助手草稿上方的「离线下载这个链接」提示条(FetchUi / FetchViewModel)
  feature/shares/                「分享」tab(SharesScreen:「分享」「收集」两个标签;我的分享与操作、访问记录)与新建分享对话框(ShareCreate,文件页 / 搜索页 / 预览共用)
  feature/collect/               收集文件:「收集」标签的列表与操作(FileRequestsTab)、新建 / 修改对话框(FileRequestEdit,文件页 / 搜索页文件夹的「收集文件」共用)、
                                 收件记录(FileRequestUploads,路由 Routes.FileRequestUploads 挂在「分享」tab 的嵌套图里)
  feature/notifications/         「我的 → 通知」:NotificationsScreen / NotificationsViewModel(按天分组、只看未读、全部已读、清空、点开跳转)、
                                 NotificationsLogic(目标 → 页面、分组,纯函数)、NotificationsEntryViewModel(角标轮询、系统通知开关)、NotificationPermission
  feature/links/                 直链:CopyLink(文件页 / 搜索页「复制直链」与格式对话框)、DirectLinksTab / DirectLinksViewModel(「分享」tab 的「直链」标签)
  feature/activity/              「我的 → 动态」:ActivityScreen / ActivityViewModel(按天分组、同一批折叠、按来源筛、单条 / 整批 / 按来源撤回,
                                 整批先预览;大量删除提醒点进来按来源和时间筛好)、ActivityLogic(文案、分组、撤回提示、通知目标里的来源,纯函数)
  feature/space/                 「我的 → 空间」(用量条也能点进来):SpaceScreen 七个标签,一个标签一个 ViewModel(SpaceViewModels);
                                 SpaceLogic(类型名、占比、文件夹分布、重复文件默认勾选,纯函数)
  feature/admin/                 管理端:AdminScreen(概览卡片、失败任务重排、用户列表与操作对话框)/ AdminViewModel
  feature/about/                 UpdateDialog(检查 / 下载 / 交给系统安装器)、CrashLogScreen(查看、导出、删除)、LicensesScreen(依赖与许可证全文)
  feature/preview/               全屏预览(PreviewScreen:翻页器、顶栏、沉浸)与平板侧栏(PreviewPane);PreviewViewModel(会话列表、最近 6 页)、
                                 PreviewPage(单页加载与 Office 轮询)、PreviewPages(各类型的页面)、PdfDocument(PdfRenderer 串行封装)、
                                 PlaybackService(音频后台播放的 Media3 会话服务:队列、按需解析地址、进度上报)、MediaControllers
  feature/feeds/                 「我的 → 订阅」:列表、添加(贴地址 → 预览 → 选方式)、详情(条目分页、处理中时轮询、立即检查、暂停、退订);
                                 FeedsLogic(标签、能做的操作、轮询合并)、FeedsGraph(订阅与剪藏的导航,挂在「我的」图里)
  feature/clips/                 「我的 → 剪藏」列表与阅读页(停留 3 秒自动已读)、MarkdownView(WebView:关 JavaScript、不碰本地文件)、
                                 ClipShareActivity(系统分享「剪藏到 gopan」,manifest 里的 activity-alias .ClipShareTarget 指过来)、
                                 预览里 Markdown 页的 MarkdownPageViewModel(剪藏来源条)
  feature/media/                 影音的纯逻辑(MediaLogic:续播、进度文案、队列、下一集、跟随翻页)、最近播放(RecentPlaysScreen / ViewModel,
                                 文件页菜单进)、迷你播放条(MiniPlayer,MainScaffold 底部导航上面)
app/src/test/kotlin/             JVM 单测;testutil/ 里有假 gopan 服务端(FakeGopanServer:Login/Refresh/Me/Logout/ChangePassword 与票据入口)
                                 与装配好的 TestHarness
```

功能线的规则:只改自己 `feature.<x>` 包下的文件、自己的 `res/values/strings_<x>.xml` 与测试;
共享文件(`Routes`、`AppModule`、`MainScaffold`、`AndroidManifest`、主题)需要改动时写进返回结果,由 Integrate 阶段统一做。
GraphQL operations 按域各自一个文件(`nodes.graphql` 归文件线,`chat.graphql` 归传输助手线……),改了 schema 重新 `assembleDebug` 即重新生成。
本地数据库只有一个 `core.data.db.GopanDatabase`(版本 2):目录首页缓存 + 上传 / 下载队列。只允许从版本 1(只有可丢的缓存)
破坏性升级;队列表里有用户的进行中任务,以后改表结构必须写 Migration。
回收站页注册在文件 tab 的嵌套图里(`Routes.Trash`),同一 NavHost 内从任何 tab `navigate(Routes.Trash)` 都能进(「我的」页的入口就是这样)。

## 协议要点

- GraphQL:`POST {base}/query`,Apollo Kotlin 4 直接用 `../server/graph/schema.graphqls` 生成(`xyz.yophon.gopan.graphql`)。
  标量映射:`Time` → `String`(RFC3339),`Int64` → `Long`。错误看 `errors[].extensions.code`(HTTP 几乎总是 200),
  Repository 统一用 `ApolloResponse.requireData()` 转成 `GopanException(code, 中文 message)`。
- 认证与网页端一致,后端不改:
  - `login` / `register` 返回 access token(服务端默认签 4 小时,另存一份进 SecureStore)并 `Set-Cookie: gopan_rt`(Path=/query,14 天,每次 refresh 轮换);
    `GopanCookieJar` 只收基址 host 的 cookie,序列化后存 EncryptedSharedPreferences。
  - 业务请求带 `Authorization: Bearer`;`AuthInterceptor` 拿到 `UNAUTHENTICATED` → `SessionManager` 单飞 refresh → 成功换新 token 重放一次;
    服务端拒绝 refresh → 清 cookie、`AuthState.SignedOut`(自动回登录页);只是网络不通 → 保留登录态,本次按网络错误返回。
  - `Login/Register/Refresh/Logout` 四个 operation 不带 Bearer、不触发刷新。
  - 启动:有 cookie 且存过上次的用户就不等网络。存下的 access token 离过期还有 5 分钟以上(`AccessTokens` 读 JWT 的 exp)就带着它进入 SignedIn,
    这次不 refresh,请求立刻发出(会话被吊销时首个请求吃 UNAUTHENTICATED,走下面的 refresh,被拒回登录页);
    否则用上次的用户进入 SignedIn(token 为空),refresh 在后台跑——成功换上 token,
    被拒清会话回登录页,断网保持现状(首个请求联网后自动刷新);没存过用户才停在启动页等 refresh(最多 8 秒)。
    token 为空时 `AuthInterceptor` 先等那次单飞 refresh(`TokenRefresher.accessTokenForRequest`)再发,
    不会先吃一个 UNAUTHENTICATED 再重放;refresh 刚因网络失败的 5 秒内不再等,直接不带 token 发出。
- 先显示后刷新(stale-while-revalidate):文件页进目录先画 Room 里的首页缓存(`NodesRepository.cachedFirstPage`),
  联网结果回来再换,首次联网还在路上时 `revalidating` 为真(不出离线提示条,顶上一条细进度);
  其它列表页用 `ScreenCache`(按用户隔离、登出清空、内存里 LRU 64 条)存上次的首屏,ViewModel 进来先画它再静默刷新,
  `ScreenCache.mirror` 让本地的乐观修改也留在快照里。两个底部 tab(传输助手最新一页、我的分享)的键带序列化器,
  还经 `SnapshotDisk` 存到 noBackupFilesDir/snapshots(内容变了才写),冷启动后内存里没有时 `ScreenCache.load` 读回来:
  进主界面时 `StartupPrefetch` 先读盘再联网预取,页面自己也会读盘,所以一打开 App 就切过去也不用转圈。
  首次加载的整页转圈延迟 300ms 出现,文件页用骨架行;NavHost 转场统一 180ms 淡入淡出(`core/ui/nav/NavTransitions.kt`)。
  - `changePassword`:服务端吊销其它设备并给当前会话重发一对 token,客户端直接采用(与网页端相同),不需要重新登录。
- Yophon ID 原生登录(`core/auth/YophonIdLogin` + `TicketExchange`):
  1. AppAuth 取 `{issuer}/.well-known/openid-configuration`,授权码 + PKCE,`client_id=gopan-android`,
     `redirect_uri=xyz.yophon.gopan:/oauthredirect`,scope `openid profile offline_access`;
     Custom Tabs 回调由 AppAuth 的 `RedirectUriReceiverActivity` 接收(manifestPlaceholder `appAuthRedirectScheme`)。
  2. 公开客户端换 token(`NoClientAuthentication`),账号中心的 access token 只在内存。
  3. `POST {issuer}/api/mobile/ticket` `{"client_id":"gopan","challenge":S256(verifier)}`(Bearer 账号中心 token)→ `{ticket, entry}`;
     `entry` 必须等于 `{base}/api/auth/id/mobile`,否则拒绝。
  4. `POST entry` 表单 `ticket`/`verifier`/`return=/drive`,不跟随跳转,期待 303 + `Set-Cookie: gopan_rt`;403 → 「账号未授权或登录暂不可用」。
  5. `refresh` 拿 access token → `SignedIn`,之后与密码登录完全一样。账号中心撤销设备 → refresh 失败 → 自动登出。
- 预签名 URL(`thumbUrl` / `downloadUrl` / 上传 `partUrls`)15 分钟有效且走对象存储域名:不要长期缓存 URL 本身,
  图片缓存按 `node.id + updatedAt` 作 key;OkHttp 对这些域名不会带 cookie / Bearer。

## 传输引擎(P2)

- **执行**:`TransferWorker`(WorkManager 唯一任务,网络约束 CONNECTED,「仅 Wi-Fi」时 UNMETERED)提升为 dataSync 前台服务,
  调 `TransferEngine.drain()` 跑到队列空;通知栏常驻进度,结束时 App 在后台才发结果通知。进程被杀、手机重启后由 WorkManager
  与 `TransferEngine.onAppStart()` 重新排;后台启动前台服务被拒(Android 12+)时退化成普通 Worker 继续跑。
- **队列**:Room 两张表,状态 QUEUED / RUNNING / PAUSED / FAILED / DONE / CANCELED;状态迁移全部是带前置状态的 UPDATE,
  运行结果落地时用户刚暂停 / 取消就以用户为准。实时进度只在内存(约 3 次/秒),库里只在迁移时写。
  并发:同时 2 个上传文件(每个文件按网络 2–3 片并发,可在传输设置里改)、3 个下载。
- **重试**:瞬时错误指数退避(2s 起,封顶 60s),5 次后进 FAILED;网络类错误(NETWORK / HTTP)不计上限,5 次后每 5 分钟一次,
  新一轮 Worker 或系统网络恢复时立即重试。
- **上传**(`UploadRun`,协议见 doc/03):流式 SHA-256(哈希按 size + 修改时间缓存在任务里)→ `initUpload`(命中秒传直接完成)
  或 `uploadSession` 对账(只传服务端说缺的片)→ 预签名分片并发 PUT(取到 50 分钟主动换签名,403 被动换,同一片连续两次 403 判定签名配置有问题)
  → `completeUpload` 轮询(前 10 次 1 秒,之后 ×1.5 封顶 5 秒,10 分钟后 30 秒)。定稿撞名、会话过期、传输助手文件夹被删、
  内容在上传途中被改时自动重建会话(最多 3 次);确定性失败时 abort 会话。不可 seek 的来源(管道)边哈希边拷进私有目录。
- **下载**(`DownloadRun`):`node(id){downloadUrl sha256}` 现取现用(URL 不落库,取到 10 分钟后在下一次建连前重取,403 重取一次)
  → GET + `Range: bytes=<.part 长度>-`(`Accept-Encoding: identity`,不带任何凭证)→ 校验 SHA-256 → 发布:
  Android 10+ 写 MediaStore `Download/gopan/<相对目录>`,8–9 写公共「下载/gopan」(需存储权限),或传输设置里选的 SAF 目录;
  「打开」下到 `cacheDir/open/<sha256>/`(同内容第二次直接命中)后弹选择器。内容被覆盖写(sha / size 变了)时 .part 作废。
  文件夹在客户端广度遍历展开成子任务(能续传、没有 2 GiB 上限);打包走 `GET /pack`(Bearer),≤ 2 GiB,不能续传,
  下完用 `ZipFile` 读中央目录校验(服务端中途出错时 HTTP 层也是正常结束)。
- **来源与授权**:SAF / 相册选择器的 URI 持久化读授权(任务结束释放);系统分享的文件收到就拷进 `filesDir/share-incoming`
  (分享授权只在接收页任务存活期间有效),拍照与长文本放 `filesDir/transfers/`;登出时清空队列与 transfers 目录。

## 传输助手(P2)

行为与网页端一致(doc/05、doc/17),并修掉了网页端的几处问题:排序、去重、"最新页覆盖范围"一律按消息 id(uuid v7);
同一 id 用服务端的新副本覆盖本地(文件被删后立刻显示「文件已不存在」,缩略图地址自动续签);可以连发;发送前清搜索、离开定位模式;
长文本(> 4000 字节)入队成功后才清草稿;删除时 NOT_FOUND 当成功;上传卡片按 nodeId 等对应消息出现再撤。
只在页面可见(STARTED)时每 2 秒刷新,进后台即停;回到前台、系统网络恢复时立即刷新。
系统分享:文本接到草稿后面(不自动发),文件直接发;未登录时暂存,登录后处理。

## 预览(P3)

- **入口**:文件页、搜索结果、传输助手里点文件 → 全屏预览,左右翻的是打开时那个列表里的文件(传输助手是当前显示的消息,从旧到新)。
  列表放在 `PreviewSessionStore`(路由只带起点 nodeId);进程被回收后只剩当时看的那一个文件。平板(宽 ≥ 840dp)文件页点文件在右侧栏预览。
  从内容搜索结果打开时每项带命中页(`PreviewItem.pdfPage`),按 PDF 显示(含 Office 派生)的页列表一打开就停在那一页;其它入口都从第一页看。
- **加载**:每页现取 `node(id){preview{…}}`(预签名 15 分钟),只留最近 6 页,屏幕上(含左右各预加载一页)的不释放。
  - 图片:2048 大图派生(没有派生时原图,解码边长封顶 4096),加载出来之前用列表缩略图垫底;双指 / 双击缩放,放大时不翻页;「查看原图」换原图。
  - 视频:页面内 ExoPlayer,翻到才缓冲并自动播放,翻走暂停,离开即释放;播放控件显隐与顶栏同步。
  - 音频:交给 `PlaybackService`(Media3 会话服务,mediaPlayback 前台服务 + 通知控制),离开预览、锁屏照常播;
    预签名过期时播放器拿到 403 按 mediaId(节点 id)重取地址、从原位置续播(视频同理)。
  - 影音(doc/22 二):视频挂外挂字幕(`/sub/{id}` 的 WebVTT,Bearer 走请求头),控件里的字幕按钮也能选 mkv 内封轨道;
    播放进度每 15 秒、暂停、离开时上报,PDF 停在一页 5 秒后上报;打开时没看完(超过 10 秒)自动续上并提示「从头播放」,
    PDF 停在上次的页(内容搜索的命中页优先)。音频把同文件夹的音频(`playlist`)整个排进队列,地址开播时才取;
    上一首 / 下一首、列表循环 / 单曲循环、随机;播放器换曲时预览页跟着翻。主界面底部导航上面有迷你播放条。
    视频放完有下一集时倒计时 5 秒接着放(在当前翻页列表里就翻过去,不在就换成同文件夹的视频列表)。
  - PDF(含 Office 派生):下到缓存后用系统 `PdfRenderer` 逐页渲染(同一时刻只能开一页,全部串行在一个线程上);点一页全屏 2.5 倍清晰度放大。
    加密或损坏的 PDF 引导「用其他应用打开」。
  - 文本:按 512 KB 一段用 Range 读(服务端只把 1 MB 以内的文本归为可预览),第一段识别编码(UTF-8 解不开按 GB18030),
    段尾半个字符留给下一段;开头与末尾各有一条「已显示 x / 共 y · 继续加载」,滑到末尾自动接着读;分块交给 LazyColumn,几百 KB 也不卡。
  - Office:没有派生时触发一次转换,转换中每 2 秒查一次(只在这页显示着时查);本站没开转换(UNAVAILABLE)、转换失败都给「用其他应用打开」,失败可重试。
- **顶栏**:下载、更多(查看原图 / 分享链接 / 用其他应用打开 / 发到传输助手);单击收起(连同系统栏,沉浸看图)。

## 分享与账户安全(P3)

- 分享:文件页 / 搜索页的「分享」与预览里的「分享链接」打开同一个对话框(有效期 1 / 7 / 30 天或永久、可选密码,打开密码保护时预填 4 位随机码);
  创建后显示链接(`{base}/s/{token}`)与密码,「发送」拉起系统分享面板,「复制」连密码一起。「分享」tab:我的分享(访问 / 下载计数是近 180 天的)、
  复制 / 发送 / 浏览器打开链接、访问记录(最近 50 条)、取消分享。
- 登录设备:本机标「本机」,吊销其它设备后那台下次 refresh 被登出;App 的请求都带 `gopan-android/<版本> (Android <版本>; <厂商> <型号>)`,
  设备列表与访问记录里显示为「gopan 安卓 App」(网页端同一条规则)。
- WebDAV 应用密码与 Agent 接入(API Key、OAuth 授权、目录范围、操作记录)与网页端对话框一致;新生成的密码 / Key 明文只在这一页显示一次。

## 收集文件(doc/20 C2)

只做属主侧,行为与网页端一致;访客不装 App,在浏览器里打开 `{base}/c/{token}` 上传。

- 入口:文件页 / 搜索页里文件夹的「更多」→「收集文件」(只对文件夹)。对话框:标题(默认「往「文件夹」传文件」)、说明、截止 1 / 7 / 30 天或不限(默认 7 天)、
  要求留名、按人分文件夹(开了强制要求留名)、单个文件 / 总量(默认 10 GB)/ 个数上限、密码保护(只在新建时,打开时预填 4 位随机码;建好后不能改)。
  建好后显示链接与密码,「复制」「发送」连标题、链接、密码一起。修改是整表单提交:截止默认「不变」,已有的上限不在预设里时多出一个选项,不会被悄悄改掉。
- 「分享」tab 的「收集」标签:标题、文件夹、收件统计、「新 N」、截止(已截止标红);点一行进收件记录,⋮ 里有收件记录 / 复制 / 发送 / 打开链接 / 修改 / 结束收集。
- 收件记录:新的在前,滑到底按 cursor 续拉,下拉刷新;打开时有新收到的就标为已读。文件还在时点一条跳到它所在的目录,删掉的划掉、灰掉(记录保留)。

## 外观与平板(P3)

- 「我的 → 外观」:深浅主题、配色、列表视图。配色三选一:墨黑(默认,与网页版同一套)、青绿(3.5 以前的配色)、
  跟随壁纸(Material You,Android 12+ 才出现);偏好键 `palette`,3.6 以前的 `dynamic_color` 开关不再读,第一次改配色时删掉。
- 视觉(3.6):`core/ui/theme` 里墨黑配色的 Material 角色 + `GopanColors`(强调色、文件夹两片、面板、底部导航、文件类型图标),
  青绿与跟随壁纸的 `GopanColors` 从各自的配色推出来;标题加粗(`GopanTypography`)、圆角 6/8/12/16/24(`GopanShapes`)。
  底部导航是浮起的胶囊,选中项垫一块会滑动的底;文件页的新建按钮与未读角标用信号橙;文件夹是两片的实心图标(`FolderGlyph`);
  登录页与「我的」头部是文件夹面板(`FolderPanel`,标签页宽度跟着内容走);四个 tab 的根页面顶栏是粗体大标题(`TabTitle`)。
  启动图标是墨黑底白色文件夹(矢量,带 Android 13 主题图标的单色层),与网页版图标同一条路径。
- 宽 ≥ 600dp(横屏、平板、折叠屏展开)底部导航换成侧边导航栏;宽 ≥ 840dp 文件页是列表 + 预览双栏,返回键先收起侧栏。
  文件、分享、收集、直链的列表按宽度分栏(`ListColumnMinWidth` 340dp,手机横屏两栏);「我的」在宽屏左边放头部、右边放设置。
- 手势与滚动(3.6):
  - 底部胶囊浮在页面上、背后透明:MainScaffold 把它连同迷你播放条叠在 NavHost 上面,量出的高度经 `LocalBottomBarInset` 下传;
    `NestedScreenInsets` 只含这段底部,TopAppBar 用 `TopBarInsets`(零)。文件、分享三个标签、「我的」的内容铺到底、
    底部留白放进列表的 contentPadding,能滚到胶囊后面;其余页面照旧让在胶囊上面。
  - 胶囊可以点,也可以按住左右拖:白底跟手、经过 tab 轻震,松手切过去。再点当前 tab:在子页面就退回首页,在首页就滚回顶部
    (`OnTabReselect`,传输助手是滚到最新)。
  - 分享、搜索、空间、传输列表的标签能左右滑(`rememberTabPager` + HorizontalPager);Agent 接入的标签栏在列表里,横划切换(`swipeBetweenTabs`)。
  - 文件、分享、「我的」的顶栏随滚动收起(enterAlways);文件页新建按钮往下滚收起、往上滚出来。
    传输助手顶栏与输入栏一收一放:手指上推收顶栏、下拉翻历史收输入栏,停在最新处或弹出键盘时输入栏一定露出来。
  - 传输助手的长按在 Initial 阶段先判断(`longPressFirst`),整条都是链接的消息按在链接上也能长按。
  - 全屏看图片(没放大时)上下拖关闭;列表增删、换顺序有条目动画(`animateItem`)。

## 管理端(P4)

「我的 → 管理端」只对管理员显示(`me.isAdmin`),与网页端同一组 GraphQL:概览(用户数、逻辑 / 物理用量、任务队列,失败任务数大于 0 时可一键重排)、
用户列表(管理员排前)、新建用户(用户名 2–32 个字符、密码至少 8 位、配额按 GB 填)、调配额、禁用 / 启用(不能禁用自己)、重置密码
(新密码只在对话框里显示一次,可复制)。服务端的禁用、重置都会立刻踢下线对方全部设备。

## DocumentsProvider(P4)

`core.documents.GopanDocumentsProvider`(authority `${applicationId}.documents`)把 gopan 挂进 Storage Access Framework:
系统「文件」App 的侧栏、别的应用的「打开文件 / 另存为」面板里都有 gopan。

- 根:只在登录后出现(副标题是用户名),`DocumentsRootNotifier` 在登录态变化时通知系统刷新;登出时清掉读缓存与没写完的新文件。
- 文档 id:根是 `root`,节点是服务端节点 id,还没传完的新文件是 `pending:<uuid>`。方法都在 binder 线程上,网络请求直接阻塞。
- 列目录走 `NodesRepository`(一个目录最多 2000 项),搜索走 `searchNodes`;有缩略图的文件提供缩略图(另存一份小缓存)。
- 读:`DocumentCache` 整份下到 `cacheDir/documents`,按 SHA-256 校验,同内容只下一次,总量超过 1 GB 按最久未用淘汰;取消信号会中断下载。
- 写:已有文件只读(服务端没有版本与覆盖写,`w` 模式直接拒绝);`createDocument` 建文件夹立即调服务端,建文件则先在
  `filesDir/transfers/documents` 落一个空文件,对方写完关闭描述符时进上传队列,传完通知目录刷新;列目录时把还在传的新文件一并列出。
- 重命名、删除(进回收站)直接调服务端。

## 检查更新、崩溃日志与开源许可(P4)

- 检查更新:`GET {base}/api/app/android/latest`(服务端 `httpx.AndroidRelease`,发版目录见 doc/09);`versionCode` 比自己大才算新版本。
  下载到 `cacheDir/update`,必须带 SHA-256 且校验通过才交给系统安装器(FileProvider + `ACTION_VIEW`);没有「安装未知应用」授权时先引导去系统设置。
  仓库是私有的,App 不读 GitHub Releases。
- 崩溃日志:`CrashLog` 在 `GopanApp.onCreate` 最先装上,未捕获异常写 `filesDir/crash`(只留最近 20 份,带版本号、设备、线程与堆栈),
  写入前抹掉 URL 查询串(预签名签名)与 Bearer token,然后交还系统默认处理。「我的 → 崩溃日志」查看、导出(系统分享)、删除。
  release 包是混淆过的:用同版本的 `mapping.txt` 跑 `retrace` 还原。
- 开源许可:`feature.about.OSS_LIBRARIES` 列出打包进 App 的第三方库(含 Guava、Tink 这类传递依赖),许可证全文在 `res/raw`:
  `license_apache_2_0.txt` 是 Apache License 2.0 的条款全文(到 END OF TERMS AND CONDITIONS 为止,取自 `web/node_modules/typescript/LICENSE.txt`,段落不折行),
  `license_mit.txt` 是 MIT 许可的正文(取自 graphql-js 的 LICENSE,去掉版权行,各库的版权人列在表里)。
  加依赖或升级后用 `./gradlew :app:dependencies --configuration releaseRuntimeClasspath` 核对这张表。

## R8(P4)

release 开 `isMinifyEnabled` + `isShrinkResources`,规则在 `app/proguard-rules.pro`:依赖库自带 consumer 规则,这里只补
kotlinx.serialization 的生成类(类型安全路由、Cookie 持久化)、AppAuth 的 JSON 模型,以及崩溃日志要的行号。

混淆规则漏一条往往只在运行时炸,所以有 `releaseE2e` 构建类型:与 release 同一套 R8 和规则,但 debug 签名、`TEST_HOOKS=true`、
放行本机明文 HTTP,能装进模拟器连本地栈把 `tools/e2e` 的 p1–p4 全跑一遍。改了规则或加了依赖都要这样跑一次。
CI 也会出一个 releaseE2e 包,保证 R8 至少能过编译期检查。

每次正式发版把 `app/build/outputs/mapping/release/mapping.txt` 压成 `gopan-<版本>-mapping.txt.gz`,与安装包一起存档(`releases/gopan/`、GitHub Release)。

## 代码规则

- 网络/IO 不在主线程;ViewModel 暴露 `StateFlow<UiState>`;UI 无业务逻辑。
- 不打印 token、cookie、预签名 URL。
- 纯逻辑抽成可单测的函数;协议层用 MockWebServer 测(见 `core/auth/SessionManagerTest`、`TicketExchangeTest`)。
- 界面全部中文;不接任何第三方统计/崩溃上报。
