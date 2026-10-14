# gopan 安卓原生客户端

Kotlin + Jetpack Compose(Material 3)重写的 gopan 客户端,替代 v2.6 的 Capacitor 壳(`app/`)。
总计划见 `doc/19-安卓原生App计划.md`;本目录是 P1 的工程骨架与认证/网络核心。

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

## 目录结构

单模块 `:app`,按包分层(模块化留到 P3):

```
app/src/main/graphql/            GraphQL operations(按域一个文件,字段选择与 web/src/api/operations 一致)
app/src/main/kotlin/xyz/yophon/gopan/
  GopanApp.kt                    @HiltAndroidApp;启动时跑 SessionManager.startBootstrap()
  MainActivity.kt                唯一 Activity:GopanTheme → RootNavHost
  MainViewModel.kt               登录态 + 主题偏好
  RootNavHost.kt                 按 AuthState 切换:启动页 / 登录图 / MainScaffold
  MainScaffold.kt                底部导航(文件 / 传输助手 / 分享 / 我的)+ 主 NavHost —— 底部栏与 NavHost 只在这里
  di/AppModule.kt                Hilt 根模块:AppConfig、OkHttp、Apollo、SecureStore、DataStore
  core/AppConfig.kt              服务端/账号中心地址(BuildConfig → 对象,测试可替换)
  core/network/                  SecureStore(加密 KV)、GopanCookieJar、OkHttp/Apollo 工厂、AuthInterceptor、GopanException
  core/auth/                     AuthState/UserSummary、SessionStore、SessionManager、Pkce、TicketExchange、YophonIdLogin
  core/prefs/                    UserPreferencesRepository(DataStore:主题、列表视图、排序)
  core/ui/theme|components|format|nav   主题、通用组件(Loading/Error/Empty/Confirm/TextInput/Placeholder)、格式化、Routes
  feature/{login,drive,chat,shares,account}/<Feature>Graph.kt   各功能线的导航图,Scaffold 阶段全是占位
app/src/test/kotlin/             JVM 单测;testutil/ 里有假 gopan 服务端(FakeGopanServer)与装配好的 TestHarness
```

功能线的规则:只改自己 `feature.<x>` 包下的文件、自己的 `res/values/strings_<x>.xml` 与测试;
共享文件(`Routes`、`AppModule`、`MainScaffold`、`AndroidManifest`、主题)需要改动时写进返回结果,由 Integrate 阶段统一做。
GraphQL operations 按域各自一个文件(`nodes.graphql` 归文件线,`chat.graphql` 归传输助手线……),改了 schema 重新 `assembleDebug` 即重新生成。

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
