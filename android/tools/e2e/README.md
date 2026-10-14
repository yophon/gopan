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
cd tools/e2e && node p1.mjs     # 12 个场景,失败截图在 .run/fail-*.png
node stack.mjs stop
```

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

## 文件

- `stack.mjs` 本地栈启停(`start` 幂等;`--rebuild` 强制重建服务端)与 `idAdmin`(账号中心管理脚本,stdin 传 JSON)
- `device.mjs` adb / uiautomator 封装;`chrome.mjs` 模拟器 Chrome 的 CDP;`app.mjs` App 页面操作
- `gql.mjs` 主机侧 GraphQL 客户端(含上传协议);`data.mjs` 造数据
- `.run/` 运行数据(账号、数据库、日志、截图),已 gitignore
