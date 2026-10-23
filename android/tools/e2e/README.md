# 本地全链路 E2E

真实后端 + 模拟器里的 App:本机起一个隔离的账号中心(Yophon ID)开发实例和一个 gopan 实例,
模拟器经 `adb reverse` 访问它们,原生登录走模拟器里的 Chrome Custom Tab。脚本零 npm 依赖(Node 24)。

只连临时数据,不碰生产(drive.979687.xyz / id.979687.xyz)。

## 依赖

- Postgres 与 MinIO:`127.0.0.1:5439`(gopan/gopan,库 `gopan_e2e`)、`127.0.0.1:9009`(gopan / gopan-minio-dev)。
  本机没有 Docker 时,在服务器上起一次性容器并开 SSH 隧道:
  ```sh
  ssh yophon 'docker run -d --name gopan-testpg --memory=256m -p 127.0.0.1:5439:5432 -e POSTGRES_USER=gopan -e POSTGRES_PASSWORD=gopan -e POSTGRES_DB=gopan_e2e postgres:17-alpine && docker run -d --name gopan-testminio --memory=256m -p 127.0.0.1:9009:9000 -e MINIO_ROOT_USER=gopan -e MINIO_ROOT_PASSWORD=gopan-minio-dev minio/minio:latest server /data'
  ssh -N -L 5439:127.0.0.1:5439 -L 9009:127.0.0.1:9009 yophon   # 保持开着
  ```
- 账号中心源码:`../yo-style/yophon-id`(相对仓库根;`E2E_ID_SRC` 可改),需要已 `bun install` 并构建 `frontend/dist`。
- Go(`~/.tools/go/bin/go`)、pnpm、bun、JDK 21、Android SDK;模拟器 `emulator-5554`,**内存至少 4 GB**
  (`emulator -avd <name> -memory 4096 ...`;2 GB 时 API 36 + Chrome 会让 adbd 卡死,所有 adb 命令超时)。
- 脚本只在 `emulator-*` 上运行;真机需显式 `E2E_ALLOW_PHYSICAL=1`(会清除 App 数据,不要对日常手机用)。

## 运行

```sh
cd android/tools/e2e
node stack.mjs start            # 构建 gopan-e2e.exe(必要时;--rebuild 强制)、起账号中心与 gopan、建 demo / owner / other 账号、adb reverse
cd ../.. && ./gradlew :app:assembleDebug -Pgopan.baseUrl=http://localhost:8090 -Pgopan.idIssuer=http://localhost:3040
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
cd tools/e2e && node p1.mjs     # P1:12 个场景,失败截图在 .run/fail-*.png
node p2.mjs                     # P2:上传 / 下载 / 传输助手 / 系统分享,失败截图在 .run/p2-fail-*.png
node p3.mjs                     # P3:预览 / 分享 / 账户安全 / 主题 / 平板,失败截图在 .run/p3-fail-*.png
node p4.mjs                     # P4:管理端 / 检查更新 / 崩溃日志 / 开源许可 / 「文件」App / 联网自动刷新,失败截图在 .run/p4-fail-*.png
node cleanup.mjs                # 只在中途打断后需要:删掉留下的 0p2-* / 0p3-* / 0p4-* 工作目录并清空回收站
node stack.mjs stop
```

**R8 包也要跑。** 正式包开了 R8,混淆规则漏东西只在运行时炸。`releaseE2e` 构建类型与 release 同一套 R8,但 debug 签名、
带测试钩子,装上后四套照样跑(debug 包与它签名相同,`install -r` 直接覆盖):

```sh
cd android && ./gradlew :app:assembleReleaseE2e -Pgopan.baseUrl=http://localhost:8090 -Pgopan.idIssuer=http://localhost:3040
adb -s emulator-5554 install -r app/build/outputs/apk/releaseE2e/app-releaseE2e.apk
cd tools/e2e && for s in p4 p3 p1 p2; do node $s.mjs > .run/$s-r8.log 2>&1; done
```

**正式签名的包**用 `node release-smoke.mjs <app-release.apk>`:连生产账号中心但不登录,只核对授权请求;会先卸载模拟器上的 App,结束时再卸载 release 包。

本地栈的 gopan 用 5 MiB 分片(`GOPAN_PART_SIZE`,S3 允许的最小值;`E2E_PART_SIZE` 可改),几十 MB 的文件就有十几片,
续传场景才测得出来。对象存储在服务器上、经 SSH 隧道,上行约 0.5 MiB/s:大文件场景默认 48 MiB(`E2E_P2_BIG_MB` 可改)。
`E2E_ONLY=S1,S6` 只跑指定场景。

跑完记得用默认参数重新 `assembleDebug`,别让指向本地栈的产物留在 build 目录里。

各套件开始时清 App 数据、重新登录,结束时删掉自己的工作目录并清空回收站;p1 的 E0 还会顺手清掉之前留下的 `e2e-*` / `0p[234]-*` 目录。
p1 → p2 → p3 → p4 任意顺序都能跑,但 p4 的 K2(失败任务重排)要库里有失败任务:本地栈的 Office 转换服务不通,跑过 p3 才会有,否则跳过。

## 场景(p1.mjs)

| 场景 | 验证点 |
|---|---|
| A | Yophon ID 原生登录:授权请求 client_id / redirect_uri / PKCE S256 / offline_access / 无 prompt,Chrome 填账号中心表单,回到 App |
| B | 冷启动保持登录(cookie 持久化 + 启动续登) |
| C | 账号中心 `revoke-all` 后下拉刷新即回登录页,冷启动仍是登录页 |
| D | 密码登录:错误密码、绑定 ID 的账号必须用 ID、正确登录 |
| E0–E3 | 造 205 个文件夹(服务端一页 200 条)、嵌套目录、两张 PNG(第二张秒传);分页与缩略图;新建 → 重命名 → 删除 → 回收站还原(还原后页面仍可点);搜索「所在目录」 |
| F | 主机侧改名,App 下拉刷新后标题/面包屑更新 |
| G | 断网冷启动显示缓存首页与离线提示条(撤掉 adb reverse 模拟断网:模拟器飞行模式挡不住 adb 隧道) |
| H | 深色主题 |
| J | 退出登录,冷启动仍是登录页 |

## 场景(p2.mjs)

用密码账号 other 登录;服务端状态用主机侧 GraphQL 核对。单个分享用 `am start SEND` 模拟:`am` 不会把 EXTRA_STREAM 迁移进 ClipData
(真实应用会),只给 data URI 授权,所以同一个 `content://media/...` 同时放进 data 与 EXTRA_STREAM;多文件分享(`am` 给不了
URI 列表)走模拟器自带的「文件」应用与系统分享面板(S13,界面文案按英文系统写)。文件页的场景放在根目录下一个 `0p2-<随机>` 工作目录里
(根目录有 P1 造的 200 多个文件夹)。

| 场景 | 验证点 |
|---|---|
| S0 | 清数据、密码登录、进传输助手 |
| S1 | 系统分享一张图片 → 服务端出现同 sha256 的文件消息,App 里显示 |
| S2 | 分享文本 → 进草稿,不自动发送 |
| S3 | 发文本(乐观上屏)→ 服务端收到;主机发的消息经轮询出现在 App |
| S4 | 草稿超过 4000 字节 → 转成「文本 …txt」上传 |
| S5 | 主机连传 5 张图到传输助手 → App 合成网格 |
| S6 | 大文件分享上传:传到 40% 杀进程 → 重开后按服务端已有分片续传(看 logcat「续传」)→ 70% 断网 20 秒 → 恢复后传完,sha256 一致 |
| S7 | 同一文件再分享 → 秒传 |
| S8 | 下载文件:传输列表里看到进度 → 断网 15 秒 → 续传完成,`Download/gopan` 里 sha256 一致 |
| S9 | 下载文件夹(客户端展开,子目录结构)与打包下载(zip 有 EOCD、含子目录条目) |
| S10 | 「用其他应用打开」图片 → 前台切到系统选择器 / 查看器 |
| S11 | 长按删除消息;主机彻删图片后消息保留、显示「文件已不存在」 |
| S12 | 搜索 → 定位(进入定位模式)→ 回到最新 |
| S13 | 「文件」应用里多选 20 张图 → 系统分享面板选「发到传输助手」(真实的 SEND_MULTIPLE)→ 20 张都到服务端,App 里合成 9 + 9 + 2 的网格 |
| S14 | 上传开始后锁屏并 `dumpsys deviceidle force-idle` 强制深度 Doze → 前台服务里的上传照常传完,sha256 一致 |

## 场景(p3.mjs)

用密码账号 other 登录;测试文件由主机上传到根目录下的 `0p3-<随机>` 工作目录:两张 PNG、模拟器 `screenrecord` 录的 6 秒视频
(录屏时来回滑动屏幕,静止画面录出来不到 1 秒)、90 秒正弦波 WAV、3 页 PDF、UTF-8 中文文本、GBK 文本、700 KB 日志、假的 docx、随机二进制。
主机侧用另一个 GopanClient 扮访客(验分享密码)和「另一台设备」(带 Firefox UA 登录,被吊销后 refresh 失败);
WebDAV 用 PROPFIND、MCP 用 initialize 请求核对应用密码 / API Key 的生效与吊销。

| 场景 | 验证点 |
|---|---|
| V0 | 清数据、密码登录、上传测试文件 |
| V1 | 图片:点开预览(位置 1 / 10)、双击放大后左右滑不翻页、复位后左右翻、单击收起顶栏(沉浸)、返回列表 |
| V2 | 视频:播放器读出时长(预签名地址经 OkHttp 流式播放),无播放错误 |
| V3 | 音频:自动播放(媒体会话 PLAYING)、离开预览继续放且有媒体通知、回来仍是这首、暂停 |
| V4 | PDF:逐页渲染、页码 1 / 3、点页全屏放大、返回 |
| V5 | 文本:UTF-8 中文、GBK 文件按 GB18030 解码、700 KB 日志先读 512 KB(开头提示「已显示 512.0 KB / 共 …」),「继续加载」读完第二段 |
| V6 | Office:本地栈没开转换时显示「未开启」,开了但转不了时显示「转换失败」,重试后再次失败;都有「用其他应用打开」 |
| V7 | 不支持的类型:提示,「下载」后 `Download/gopan` 里 sha256 一致 |
| V8 | 传输助手:点网格里的图进预览,右滑到上一张,返回仍在传输助手 |
| V9 | 搜索结果点文件进预览 |
| H1 | 文件「分享」:7 天 + 密码 → 链接与密码;服务端有效期约 7 天;访客错密码被拒、对密码拿到 token;「发送」拉起系统分享面板 |
| H2 | 分享页:「访问 1」、访问记录里有「打开分享」、「打开链接」交给浏览器、取消分享后链接失效 |
| H3 | 预览里「分享链接」:永久、无密码 |
| A1 | 登录设备:本机显示「gopan 安卓 App」;吊销主机那台(Firefox · Linux)后它 refresh 得到 UNAUTHENTICATED |
| A2 | WebDAV 应用密码:生成后 PROPFIND 207;吊销后 401 |
| A3 | Agent 接入:建 API Key(浏览与搜索 + 读取与下载)能通过 MCP 认证、服务端权限一致;设目录范围;吊销后 401 |
| T1 | 配色默认墨黑,换成青绿后冷启动仍是青绿(再换回墨黑) |
| W1 | `wm size 1280x800`:侧边导航栏;文件页点文件在右侧栏预览、列表仍在,点另一个文件侧栏跟着换,关闭侧栏;结束恢复原尺寸 |

## 场景(p4.mjs)

用密码账号 other 登录。K0 在临时库里用 `gopan-e2e.exe admin promote` 把它提成管理员(只连本地栈的库;没有降级命令,之后它一直是管理员,
其它套件对此不敏感:管理员的「Agent 接入」页多一段管理员 MCP,p3 的 A3 会滚动找自己的那一行)。
本地栈的 gopan 以 `GOPAN_APP_RELEASE_DIR=.run/app-release` 启动,K3 往里放一个假安装包与 `latest.json`。

| 场景 | 验证点 |
|---|---|
| K0 | 把 other 提升为管理员(主机侧核对 `me.isAdmin`)、清数据、密码登录 |
| K1 | 管理端:建号(3 GB)→ 服务端配额一致;调成 5 GB;禁用后该账号登录被拒(ACCOUNT_DISABLED)、启用后恢复;重置密码只显示一次,新密码能登录 |
| K2 | 有失败任务时下拉刷新出现「重排」按钮,点了提示「已重排 n 个失败任务」(库里没有失败任务时跳过) |
| K3 | 检查更新:没发版时提示「没有提供安卓安装包」;发版后显示版本与更新说明,`latest.apk` 固定链接 302 到当前包;下载、校验 SHA-256 → 系统安装器(或「安装未知应用」授权页);「我的」页标出「有新版本」 |
| K4 | `am crash` 让 App 崩溃 → 重开后「崩溃日志」里有一份(版本、线程、堆栈),「导出」拉起系统分享面板(抹掉签名与 token 由单测覆盖) |
| K5 | 开源许可:依赖列表(滚到末尾能看到 Tink 这类传递依赖),点开 OkHttp 看到 Apache License 2.0 全文 |
| K7 | 系统「文件」App:抽屉里有 gopan(摘要是用户名);浏览到工作目录,把 gopan 的文件复制到「下载」(sha256 一致);把「下载」里的文件复制进 gopan → 进上传队列,服务端 sha256 一致 |
| K6 | 撤掉 adb reverse 并开飞行模式 → 冷启动显示离线提示条;恢复网络后不用下拉,提示条自己消失(列表已重新拉到) |

「文件」App 的界面文案按英文系统写(模拟器默认英文),K7 依赖 `com.google.android.documentsui`。

## 文件

- `stack.mjs` 本地栈启停(`start` 幂等;`--rebuild` 强制重建服务端)与 `idAdmin`(账号中心管理脚本,stdin 传 JSON)
- `cleanup.mjs` 清掉中途打断留下的工作目录;`release-smoke.mjs` 正式签名包的冒烟
- `device.mjs` adb / uiautomator 封装;`chrome.mjs` 模拟器 Chrome 的 CDP;`app.mjs` App 页面操作
- `gql.mjs` 主机侧 GraphQL 客户端(含上传协议,可指定 User-Agent);`data.mjs` 造数据;`media.mjs` 预览用的 WAV / PDF / GBK 文本
- `.run/` 运行数据(账号、数据库、日志、截图、K3 的发版目录 `app-release/`),已 gitignore

## 踩过的坑

- 对话框、下拉菜单在单独的窗口里,testTag 要在那一层再挂一次 `exposeTestTags()` 才会变成 resource-id。
- 双击:`input tap` 两次之间隔 0.12 秒;连发两条 `input tap` 命令系统认不成双击。
- `screenrecord` 录静止画面只会得到不到 1 秒的视频,录的时候要来回滑屏幕。
- 脚本跑起来后再改,跑着的那一份还是旧的;Windows 上的 Node 在 fetch 之后 `process.exit()` 偶尔断言失败(退出码 127),
  所以脚本都用 `process.exitCode` 收尾,结果看日志里的「n/n 通过」。
- 模拟器的飞行模式挡不住 adb 隧道,断网要撤掉 `adb reverse`。
- App 不在前台时(桌面上)往下滑会拉下通知栏,之后启动 App 它也一直盖在上面,uiautomator 只看得到通知栏;
  `coldStart` 先 `cmd statusbar collapse`。「等某段文字消失」这类断言要同时认主界面在,否则被盖住时也会通过。
- 本地测试库会累积:每跑一次 p4 多一个用户,other 跑过 p4 后一直是管理员。列表里找行一律边滚边找,停在屏幕下部时再滚一点,别点到底部导航。
