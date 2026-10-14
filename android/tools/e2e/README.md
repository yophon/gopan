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
node stack.mjs start            # 构建 gopan-e2e.exe(必要时)、起账号中心与 gopan、建 demo / owner / other 账号、adb reverse
cd ../.. && ./gradlew :app:assembleDebug -Pgopan.baseUrl=http://localhost:8090 -Pgopan.idIssuer=http://localhost:3040
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
cd tools/e2e && node p1.mjs     # P1:12 个场景,失败截图在 .run/fail-*.png
node p2.mjs                     # P2:上传 / 下载 / 传输助手 / 系统分享,失败截图在 .run/p2-fail-*.png
node stack.mjs stop
```

本地栈的 gopan 用 5 MiB 分片(`GOPAN_PART_SIZE`,S3 允许的最小值;`E2E_PART_SIZE` 可改),几十 MB 的文件就有十几片,
续传场景才测得出来。对象存储在服务器上、经 SSH 隧道,上行约 0.5 MiB/s:大文件场景默认 48 MiB(`E2E_P2_BIG_MB` 可改)。
`E2E_ONLY=S1,S6` 只跑指定场景。

跑完记得用默认参数重新 `assembleDebug`,别让指向本地栈的产物留在 build 目录里。

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
| S10 | 「打开」图片 → 前台切到系统选择器 / 查看器 |
| S11 | 长按删除消息;主机彻删图片后消息保留、显示「文件已不存在」 |
| S12 | 搜索 → 定位(进入定位模式)→ 回到最新 |
| S13 | 「文件」应用里多选 20 张图 → 系统分享面板选「发到传输助手」(真实的 SEND_MULTIPLE)→ 20 张都到服务端,App 里合成 9 + 9 + 2 的网格 |
| S14 | 上传开始后锁屏并 `dumpsys deviceidle force-idle` 强制深度 Doze → 前台服务里的上传照常传完,sha256 一致 |

## 文件

- `stack.mjs` 本地栈启停(`start` 幂等;`--rebuild` 强制重建服务端)与 `idAdmin`(账号中心管理脚本,stdin 传 JSON)
- `device.mjs` adb / uiautomator 封装;`chrome.mjs` 模拟器 Chrome 的 CDP;`app.mjs` App 页面操作
- `gql.mjs` 主机侧 GraphQL 客户端(含上传协议);`data.mjs` 造数据
- `.run/` 运行数据(账号、数据库、日志、截图),已 gitignore
