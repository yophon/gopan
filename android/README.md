# gopan 安卓原生客户端

Kotlin + Jetpack Compose(Material 3)重写的 gopan 客户端,替代 v2.6 的 Capacitor 壳(`app/`)。
总计划见 `doc/19-安卓原生App计划.md`。P1 已完成:工程骨架、认证/网络核心、登录(Yophon ID + 密码登录/注册)、
文件(列表/网格、排序、分页、多选批量、新建/重命名/移动/复制/删除、回收站、搜索)与「我的」(用量、改密码、外观、退出);
传输助手(P2)、分享(P3)、设备/应用密码/Agent/管理端(P3/P4)还是占位页。

## 构建

依赖:JDK 21、Android SDK(platforms 36、build-tools 36)、Gradle 8.14.3(wrapper 自带)。

```bash
export JAVA_HOME="$HOME/.tools/jdk21/jdk-21.0.12.1+1"   # 唯一能用的 JDK:17 不能 target 21,25 Gradle 8.14 不支持
export ANDROID_HOME="$HOME/.tools/android-sdk"
cd android
./gradlew --no-daemon -q :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew --no-daemon -q :app:testDebugUnitTest      # JVM 单测(MockWebServer)
./gradlew --no-daemon -q :app:installDebug           # 有设备/模拟器时直接装
```

Android Studio 打开 `android/` 即可(Gradle JDK 选 21)。

## 签名

`android/keystore.properties`(gitignore,格式与 `app/android` 相同):

```
storeFile=D:/user/yophon/releases/gopan/gopan-release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

存在时 `assembleRelease` 用它签名;不存在时 release 退回 debug 签名(只能本地验证,不能发布)。
applicationId 仍是 `xyz.yophon.gopan`、签名同一把 key,可原地覆盖安装 v2.6 的壳;versionCode 从 30001 起。

## BuildConfig 覆盖

服务端地址等写在 BuildConfig,默认指向生产;本地联调在 `gradle.properties` 或命令行 `-P` 覆盖:

| 属性 | BuildConfig 字段 | 默认值 |
|---|---|---|
| `gopan.baseUrl` | `GOPAN_BASE_URL` | `https://drive.979687.xyz` |
| `gopan.idIssuer` | `YOPHON_ID_ISSUER` | `https://id.979687.xyz` |
| `gopan.idClientId` | `YOPHON_ID_CLIENT_ID` | `gopan-android` |
| `gopan.idRedirect` | `YOPHON_ID_REDIRECT` | `xyz.yophon.gopan:/oauthredirect` |

例:`./gradlew -Pgopan.baseUrl=http://10.0.2.2:8090 :app:installDebug`(模拟器打本机实例)。
debug 包的 `network_security_config` 只放行 `10.0.2.2` / `127.0.0.1` / `localhost` 的明文 HTTP,release 一律 HTTPS。

手机上装着 release 签名的 `xyz.yophon.gopan`(v2.6 壳或正式版)时,debug 签名的包装不进去(`INSTALL_FAILED_UPDATE_INCOMPATIBLE`),
不必卸载:`-Pgopan.debugAppIdSuffix=.dev` 让 debug 包以 `xyz.yophon.gopan.dev` 并存安装(`gopan.debugAppIdSuffix` 留空即无后缀)。
两个包都注册了 `xyz.yophon.gopan:/oauthredirect` 的 scheme 时,Yophon ID 授权回跳可能弹出应用选择器,选带后缀的那个即可。

## 目录结构

单模块 `:app`,按包分层(模块化留到 P3):

```
app/src/main/graphql/            GraphQL operations(按域一个文件,字段选择与 web/src/api/operations 一致;
                                 nodes.graphql 的 NodeItem 片段供 Children/SearchNodes/Trash/Node/CreateFolder/RenameNode 共用)
app/src/main/kotlin/xyz/yophon/gopan/
  GopanApp.kt                    @HiltAndroidApp;启动时跑 SessionManager.startBootstrap();
                                 实现 Coil 的 SingletonImageLoader.Factory,缩略图加载复用全局 OkHttpClient
  MainActivity.kt                唯一 Activity:GopanTheme → RootNavHost
  MainViewModel.kt               登录态 + 主题偏好
  RootNavHost.kt                 按 AuthState 切换:启动页 / 登录图 / MainScaffold
  MainScaffold.kt                底部导航(文件 / 传输助手 / 分享 / 我的)+ 主 NavHost —— 底部栏与 NavHost 只在这里;
                                 innerPadding 已含系统栏并 consumeWindowInsets,tab 内页面的 TopAppBar/Scaffold
                                 用默认 insets 或零 insets(feature.drive.NestedScreenInsets)都不会双倍留白
  di/AppModule.kt                Hilt 根模块:AppConfig、OkHttp、Apollo、SecureStore、DataStore;
                                 功能线自己的 @Module(core.data.DataModule、feature.login.LoginModule)由 Hilt 自动聚合
  core/AppConfig.kt              服务端/账号中心地址(BuildConfig → 对象,测试可替换)
  core/network/                  SecureStore(加密 KV)、GopanCookieJar、OkHttp/Apollo 工厂、AuthInterceptor、GopanException
  core/auth/                     AuthState/UserSummary、SessionStore、SessionManager、Pkce、TicketExchange、YophonIdLogin
  core/data/                     Node / NodeListing 模型、NodesRepository(接口)+ NodesRepositoryImpl(Apollo;文件夹 LRU 解析面包屑)、
                                 ListingCache;db/:GopanDatabase、cached_listing 表 + RoomListingCache(按 (用户, 目录) 存首页,断网回退);DataModule
  core/prefs/                    UserPreferencesRepository(DataStore:主题、列表视图、排序)
  core/ui/theme|components|format|nav   主题、通用组件(Loading/Error/Empty/Confirm/TextInput/Placeholder)、格式化、Routes
  feature/login/                 LoginScreen(Yophon ID 主入口 + 登录/注册 tab)、LoginViewModel、LoginRules(校验、错误码→文案)、LoginGateway + LoginModule
  feature/drive/                 DriveScreen / DriveViewModel(列表/网格、排序、下拉刷新、自动翻页、多选批量)、NodeActionsViewModel(新建/重命名/移动/复制/删除)、
                                 FolderPickerDialog(全屏目标选择器)、BreadcrumbBar、NodeSheets(详情/操作)、DriveGraph(文件 tab 嵌套图,含回收站与搜索路由)
  feature/trash/                 TrashScreen / TrashViewModel(还原、彻删、清空两次确认)、TrashExpiry(剩余天数)
  feature/search/                SearchScreen / SearchViewModel(300ms 防抖、分页)
  feature/account/               AccountScreen / AccountViewModel(用量条、回收站入口、改密码对话框、主题与列表视图偏好、退出登录;
                                 登录设备 / 应用密码 / Agent / 管理端是占位路由)、AccountUsage、ChangePasswordForm
  feature/{chat,shares}/         传输助手(P2)、分享(P3):目前只有占位页
app/src/test/kotlin/             JVM 单测;testutil/ 里有假 gopan 服务端(FakeGopanServer:Login/Refresh/Me/Logout/ChangePassword 与票据入口)
                                 与装配好的 TestHarness
```

功能线的规则:只改自己 `feature.<x>` 包下的文件、自己的 `res/values/strings_<x>.xml` 与测试;
共享文件(`Routes`、`AppModule`、`MainScaffold`、`AndroidManifest`、主题)需要改动时写进返回结果,由 Integrate 阶段统一做。
GraphQL operations 按域各自一个文件(`nodes.graphql` 归文件线,`chat.graphql` 归传输助手线……),改了 schema 重新 `assembleDebug` 即重新生成。
本地数据库只有一个 `core.data.db.GopanDatabase`:P2 的上传/下载队列表往里追加 entity 并把 `version` +1
(当前 `exportSchema = false`、`fallbackToDestructiveMigration`,缓存可丢;正式发布前再补 schema 导出与迁移)。
回收站页注册在文件 tab 的嵌套图里(`Routes.Trash`),同一 NavHost 内从任何 tab `navigate(Routes.Trash)` 都能进(「我的」页的入口就是这样)。

## 协议要点

- GraphQL:`POST {base}/query`,Apollo Kotlin 4 直接用 `../server/graph/schema.graphqls` 生成(`xyz.yophon.gopan.graphql`)。
  标量映射:`Time` → `String`(RFC3339),`Int64` → `Long`。错误看 `errors[].extensions.code`(HTTP 几乎总是 200),
  Repository 统一用 `ApolloResponse.requireData()` 转成 `GopanException(code, 中文 message)`。
- 认证与网页端一致,后端不改:
  - `login` / `register` 返回 15 分钟 access token(只在内存)并 `Set-Cookie: gopan_rt`(Path=/query,14 天,每次 refresh 轮换);
    `GopanCookieJar` 只收基址 host 的 cookie,序列化后存 EncryptedSharedPreferences。
  - 业务请求带 `Authorization: Bearer`;`AuthInterceptor` 拿到 `UNAUTHENTICATED` → `SessionManager` 单飞 refresh → 成功换新 token 重放一次;
    服务端拒绝 refresh → 清 cookie、`AuthState.SignedOut`(自动回登录页);只是网络不通 → 保留登录态,本次按网络错误返回。
  - `Login/Register/Refresh/Logout` 四个 operation 不带 Bearer、不触发刷新。
  - 启动:有 cookie 就 refresh;refresh 因断网无法判定时用上次缓存的用户进入 SignedIn(token 为空,首个请求联网后自动刷新),
    这样断网也能打开 App 看缓存列表。
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

## 代码规则

- 网络/IO 不在主线程;ViewModel 暴露 `StateFlow<UiState>`;UI 无业务逻辑。
- 不打印 token、cookie、预签名 URL。
- 纯逻辑抽成可单测的函数;协议层用 MockWebServer 测(见 `core/auth/SessionManagerTest`、`TicketExchangeTest`)。
- 界面全部中文;不接任何第三方统计/崩溃上报。
