# Yophon ID

可选的第一方统一登录。设置以下环境变量后，把指定的已有云盘用户绑定到账号中心的稳定 `sub`，不创建新用户、不按用户名或邮箱合并，不改动文件、配额或管理员角色。

```dotenv
GOPAN_ID_ISSUER=https://id.979687.xyz
GOPAN_ID_ORIGIN=https://drive.979687.xyz
GOPAN_ID_CLIENT_ID=gopan
GOPAN_ID_SECRET=<至少32字符的独立随机客户端密钥>
GOPAN_ID_SUBJECT=<账号中心稳定用户ID>
GOPAN_ID_USER_ID=<现有gopan用户UUID>
```

账号中心登记精确回调 `https://drive.979687.xyz/api/auth/id/callback` 和移动入口 `https://drive.979687.xyz/api/auth/id/mobile`。浏览器使用 state cookie、PKCE 和 nonce；校验 RS256、issuer、audience、sub、sid。移动入口只接收账号中心签发的绑定目标客户端与 PKCE 的一次性票据。

绑定用户的旧密码及旧浏览器会话不再提供访问权限。其他用户仍可用原登录。新的 gopan refresh cookie 仍为 HttpOnly、路径 `/query`；上游令牌以客户端 secret 派生的 AES-GCM 密钥加密存于 `id_sessions`。每次私有 Bearer 验证和续期都会检查账号中心；数据库行锁串行化上游 refresh，中心不可用时拒绝访问。前端每 30 秒及恢复到前台时检查，撤销后退出私有页面。

MCP、WebDAV 应用密码和分享凭据保持独立。已经签发的 MinIO 预签名链接持续到原过期时间，默认最长 15 分钟；即时设备撤销不能收回已经下载的内容。

部署前保存 PostgreSQL 一致性 dump、`.env`、旧镜像 ID 和 Nginx 配置。在本地构建前端和 Linux 二进制，上传后替换运行镜像；小内存服务器不要编译。迁移 `00018_identity.sql` 仅新建认证表。Nginx `/api/auth/id/` 必须 `access_log off`，后端不记录查询参数。环境密钥在受保护的部署备份中保留，账号中心客户端登记文件也有私有备份。

回滚时先停应用，备份并撤销 `id_sessions` 对应 family 的 refresh token、删除其会话，再恢复配置和旧镜像。当前代码即使禁用 provider，也拒绝把 ID 会话当作密码会话。不要回滚文件库或对象存储。

验证：`TEST_DB_URL=<隔离测试库> go test -p 1 ./...`，`go vet ./...`，前端 `bun run test`、`bun run build`。测试会重建测试库 schema，绝对不要传生产数据库。统一登录测试覆盖保留用户/配额、旧凭据隔离、state、PKCE、刷新串行、主体拒绝、撤销、故障拒绝和回滚隔离。
