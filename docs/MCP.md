# MCP Agent 工具

## 目标与边界

Gopan MCP 是集成在现有 Go 服务中的远程 MCP endpoint：

```text
Agent -- Streamable HTTP /mcp --> Gopan
Agent -- presigned PUT/GET -----> MinIO
Gopan -- verify/finalize -------> blobs/{sha256} + nodes
```

它遵守以下边界：

- 零本地安装，不提供 stdio bridge。
- 上传、下载不需要用户选择文件或打开页面。
- MCP JSON 不承载文件字节，只签发任务、URL 和状态。
- Agent 必须具备读取本地文件以及发起 HTTP PUT/GET 的其他工具。
- SHA-256 是可选输入；Agent 决定是否自行计算以争取秒传。

## Endpoint 与认证

Endpoint 是：

```text
https://pan.example.com/mcp
```

传输使用 MCP Streamable HTTP。服务采用 stateless JSON response 模式，每个请求都要带 OAuth Access Token 或 API Key：

```http
Authorization: Bearer gopan_oauth_xxx
# 或
Authorization: Bearer gopan_key_xxx
```

### OAuth 2.1（默认）

Remote MCP 客户端应优先使用 OAuth。Gopan 支持 Authorization Code + PKCE、动态客户端注册和 Refresh Token 轮换：

| Endpoint | 用途 |
|---|---|
| `/.well-known/oauth-protected-resource` | MCP 受保护资源元数据 |
| `/.well-known/oauth-authorization-server` | OAuth 授权服务器元数据 |
| `/oauth/register` | 动态注册 public client |
| `/oauth/authorize` | 发起用户授权 |
| `/oauth/token` | 授权码兑换与刷新 Access Token |

`/mcp` 返回 `401` 时通过 `WWW-Authenticate` 给出资源元数据地址。支持 OAuth 的 MCP Host 可以据此自动发现端点、注册客户端并打开一次授权确认。之后 Access Token 过期由 Refresh Token 自动轮换，不需要用户参与文件操作。

OAuth 客户端不使用 client secret，必须使用 PKCE `S256`。回调地址只接受 HTTPS，或本机 loopback 的 HTTP 地址。Access Token 有效期 1 小时，Refresh Token 有效期 30 天且每次使用后立即作废。用户在前端撤销 OAuth 授权后，该应用的所有 Token 立即失效。

### API Key（Headless 备用）

不支持 OAuth 的 Agent、CI 和服务账号使用 API Key。API Key 与登录 JWT、OAuth Token、WebDAV 应用密码彼此独立，明文只在创建时返回，数据库只保存 SHA-256。用户在账户栏的“Agent 接入”中创建、查看和吊销 Key，也可以调用 GraphQL：

```graphql
mutation {
  createMCPAPIKey(
    name: "my-agent"
    scopes: ["files:read", "files:download", "files:upload"]
  ) {
    token
    credential {
      id
      name
      scopes
      createdAt
    }
  }
}
```

管理接口还有 `mcpAPIKeys` 和 `revokeMCPAPIKey(id)`。账号被禁用、API Key 被吊销或 OAuth 授权被撤销后，`/mcp` 立即返回 `401`。

### 权限 Scope

OAuth 与 API Key 共用以下权限：

- `files:read`
- `files:download`
- `files:upload`
- `files:write`
- `files:delete`
- `shares:read`
- `shares:write`
- `audit:read`

已有五项文件权限不自动升级为分享管理或审计权限，需要重新 OAuth 授权或创建新 Key。管理员权限是独立的 `admin:read`、`admin:users`、`admin:tasks`、`admin:purge`，只能由管理员授权，且不能与普通文件权限混在同一凭据里。

## 工具

| 工具 | Scope | 作用 |
|---|---|---|
| `list_files` | `files:read` | 分页列目录 |
| `search_files` | `files:read` | 按名称搜索 |
| `get_file_info` | `files:read` | 读取节点、大小、MIME、SHA 等元数据 |
| `read_text_file` | `files:download` | 分段读取 UTF-8 文本，每次最多 64 KiB |
| `prepare_download` | `files:download` | 签发文件 GET URL 或文件夹/批量 ZIP 票据 |
| `create_folder` | `files:write` | 创建目录 |
| `rename_node` | `files:write` | 重命名文件或目录 |
| `move_nodes` | `files:write` | 批量移动 |
| `copy_nodes` | `files:write` | 批量复制 |
| `trash_nodes` | `files:delete` | 移入可恢复回收站 |
| `restore_nodes` | `files:delete` | 从回收站恢复 |
| `prepare_upload` | `files:upload` | 秒传或创建单 PUT/分片上传目标 |
| `get_upload_parts` | `files:upload` | 获取或刷新分片 URL、查看已传分片 |
| `complete_upload` | `files:upload` | 提交上传并启动服务端定稿 |
| `get_upload_status` | `files:upload` | 查询上传状态 |
| `abort_upload` | `files:upload` | 取消上传并清理 staging 数据 |
| `wait_upload` | `files:upload` | 最多等待 25 秒，返回终态或超时时的最新状态 |
| `resolve_path` | `files:read` | 按绝对路径取得节点 ID，`/` 表示虚拟根目录 |
| `create_directories` | `files:write` | 原子地递归创建目录，复用已有目录 |
| `list_trash` | `files:read` | 分页列回收站，包含节点 ID、原父目录与删除时间 |
| `batch_nodes` | 按操作使用 `files:write` 或 `files:delete` | 批量预览、逐项执行与错误反馈 |
| `storage_status` | `files:read` | 已用/总配额、预留空间、可用空间、活跃上传和传输限制 |
| `list_shares` | `shares:read` | 查看当前账号范围内的有效分享 |
| `create_share` | `shares:write` | 创建分享，可设置密码、未来到期时间和幂等键 |
| `revoke_share` | `shares:write` | 按分享 ID 撤销分享，支持幂等键 |
| `list_audit` | `audit:read` | 分页读取 MCP 操作记录 |

文件接口共 26 个工具，不暴露永久删除。永久删除只在独立管理员接口中提供。

### 配额与分享

`storage_status({})` 返回账号级配额与当前上传预留量。`available_bytes` 是查询瞬间的预估值，后台最终提交仍会重新检查配额；目录限定凭据也会看到账号级配额，不是目录单独配额。响应还包含当前凭据的 `root_id`、`scopes`、单 PUT 上限、分片大小和活跃上传上限。

```json
{"path":"/reports/result.pdf","password":"optional-password","expires_at":"2030-01-01T00:00:00Z","idempotency_key":"share-report-unique-id"}
```

上述是 `create_share` 示例，返回 `id`、`node_id`、`url`、`has_password` 和到期时间，不返回密码或密码哈希。没有密码的分享可被任何持有链接的人访问；无需对外分享时调用 `revoke_share({"share_id":"...","idempotency_key":"revoke-unique-id"})`。目录授权与分享链接是两种独立授权，修改 Agent 的目录范围不会自动撤销之前建立的分享。

### 按目录授权

网页“Agent 接入”在 API Key 和 OAuth 授权列表里提供“目录范围”。点击“全盘”或目录名，输入如 `/AI工作区`；留空保存表示恢复全盘访问。范围设置仅能通过账号网页登录管理，MCP 不提供修改自身范围的工具。

- 范围绑定 API Key ID 或 OAuth **授权 ID**，因此 OAuth 刷新令牌轮换不会丢失限制。
- 对该凭据，`/` 和省略的目标目录均映射到授权目录；路径相对这个虚拟根解析。
- 列表、搜索、回收站、分享列表只返回范围内的节点；直接传入范围外 UUID、上传会话或移动目标同样会被拒绝。
- 允许在目录内整理文件，但不能用该凭据重命名、移动、删除授权根目录本身。
- 授权根目录删除或永久删除后，凭据拒绝访问，不会自动退回全盘权限。重新选择有效目录才能恢复访问。
- 批量写操作在事务内重复校验，普通移动与 MCP 写操作共享按用户串行锁。幂等历史响应返回前也检查当前目录范围。
- 新范围对后续 MCP 请求生效。已签发的 PUT/GET URL、ZIP 票据和已启动的后台传输具有原来的有效期，不会因改范围而瞬间失效；分享需要单独撤销。

GraphQL 管理接口：`mcpAccessRoots`、`setMCPAccessRoot(credentialId, credentialType, rootPath)`。`credentialType` 为 `api_key` 或 `oauth`，`rootPath:null` 清除限制。管理员凭据不能设置文件目录范围。

### 操作记录

网页“Agent 接入 → 操作记录”可查看账号的 MCP 调用。MCP `list_audit({"limit":50,"before_id":123})` 按递减 ID 分页；目录限定凭据只能查看自己的调用历史，完整凭据可查看本账号历史。

记录包含账号/凭据 ID、接口、工具名、必要的目标 ID/路径、时间和执行结果。不会记录密码、API Key、OAuth Token、完整请求、分享链接或预签名地址。`success` 表示成功，`partial` 表示批量部分成功，`failed` 表示失败；如果进程在结束记录前退出，可能保留 `started`，此时执行结果未知，应通过幂等键或状态查询确认。日志写入失败时工具不会开始执行。

日志与幂等表均持久保存，应纳入数据库备份与容量管理。目录限制后的日志仍可能包含该凭据之前在其他范围内的操作历史，但不包含文件内容。

### 独立管理员 MCP

地址：`https://pan.example.com/mcp/admin`。具有管理员权限的 OAuth/API Key 才能调用；普通文件凭据调用返回 403，管理员凭据调用普通 `/mcp` 也返回 403。每个请求重新检查账号管理员身份和禁用状态，降权后立即拒绝后续请求。

| 工具 | 权限 | 功能 |
|---|---|---|
| `admin_overview` | `admin:read` | 用户、对象存储和任务统计 |
| `admin_list_users` | `admin:read` | 用户、配额、使用量与启停状态，不返回密码哈希 |
| `admin_list_tasks` | `admin:read` | 分页查看任务类型、状态、尝试次数 |
| `admin_list_audit` | `admin:read` | 实例所有账号的 MCP 操作记录 |
| `admin_create_user` | `admin:users` | 创建账号及可选配额 |
| `admin_set_quota` | `admin:users` | 设置配额 |
| `admin_set_disabled` | `admin:users` | 启用/禁用账号，禁止禁用自己 |
| `admin_reset_password` | `admin:users` | 生成新密码并撤销网页登录会话；不要自动重试 |
| `admin_retry_failed_tasks` | `admin:tasks` | 重排失败任务 |
| `admin_purge_nodes` | `admin:purge` | 按账号及节点 ID 永久删除选中的回收站节点 |

```bash
codex mcp add gopan-admin --url https://pan.example.com/mcp/admin
codex mcp login gopan-admin --scopes admin:read,admin:users,admin:tasks,admin:purge
```

管理员写工具除密码重置外支持 `idempotency_key`。密码重置的随机密码只作为本次响应返回，不存入幂等响应表或日志。永久删除应先 `dry_run:true` 预览，实际执行必须传 `confirm:true`；不能永久删除仍处于正常目录的节点。管理员工具只提供明确列出的应用管理能力，不提供任意 SQL、Shell 或任意文件读取。

### 客户端要求与 OAuth

客户端需要支持 Streamable HTTP、MCP 工具调用及结构化结果。文件字节不经过 MCP：

- 读取文本、搜索和整理目录，只需要 MCP。
- 上传需要读取本地文件并向预签名地址执行 HTTP PUT；下载、ZIP 打包需要 HTTP GET 与保存文件能力。
- 只支持 MCP 调用的客户端仍可签发传输地址，但不能独立完成本地文件传输。

支持 OAuth 的客户端优先使用 OAuth。Codex 示例（将 URL 替换为实际地址）：

```bash
codex mcp add gopan --url https://pan.example.com/mcp
codex mcp login gopan
```

在授权页面确认账号与所需权限。OAuth 凭据由客户端管理，访问令牌可自动刷新，不需要把长期 API Key 写入 MCP 配置。已有 API Key 接入迁移时，先移除该连接的静态 Authorization 头，再完成 OAuth 登录并验证，最后在“Agent 接入”里吊销旧 Key。API Key 仍用于 CI 等无交互环境。

参考：[OpenAI Docs：Codex MCP](https://developers.openai.com/codex/mcp)。

### 路径操作

路径以 `/` 开头，区分大小写，名称按原文匹配；不做 URL 解码或 Unicode 归一化。不支持空路径段、`.`、`..`，最多 64 层、4096 字节。可选末尾 `/`。

| 工具 | 路径参数 | 替代的 ID 参数 |
|---|---|---|
| `list_files` | `path` | `parent_id` |
| `get_file_info`、`read_text_file`、`rename_node` | `path` | `node_id` |
| `create_folder`、`prepare_upload` | `parent_path` | `parent_id` |
| `move_nodes`、`copy_nodes` | `target_path` | `target_folder_id` |
| `prepare_download` | `paths` | `node_id` / `node_ids` |
| `batch_nodes` | `paths`、`target_path` | `node_ids`、`target_folder_id` |

对应的路径和 ID 参数不能同时指定。`resolve_path({"path":"/"})` 返回 `root:true`，虚拟根目录没有节点 ID；列根目录可用 `list_files({"path":"/"})`。恢复回收站节点应使用 `list_trash` 返回的 ID，因为活跃路径解析不会返回已删除节点。

```json
{"path":"/reports/2026/September","idempotency_key":"mkdir-report-202609"}
```

以上为 `create_directories` 的参数：已有目录会复用，缺少的目录一次事务创建；路径途中遇到同名文件则全部回滚。

### 写操作重试与批量预览

`create_folder`、`create_directories`、`rename_node`、`move_nodes`、`copy_nodes`、`trash_nodes`、`restore_nodes`、`batch_nodes` 支持可选 `idempotency_key`（1–128 字节）。节点变更与响应记录在同一个数据库事务提交，因此进程重启、并发重试和响应丢失不会重复执行成功操作。

- 同一用户的节点写操作共用键空间；建议每个逻辑操作使用新的 UUID。请求指纹包含凭据和目录范围，换凭据或改范围不会重放另一授权下的结果。
- 同键、同工具、同参数返回原始结果；同键改参数返回 `IDEMPOTENCY_CONFLICT`。
- 原始响应是历史执行结果，不是节点当前状态；需要新状态时重新查询。
- 整体失败会回滚且不保存键，修正问题后可重试。
- 成功提交的记录持续保留，不自动过期，以防旧重试重复执行。大量自动化使用时需将此表纳入存储监控与备份。
- 上传生命周期沿用传输会话的幂等机制；节点写操作键与上传键不共用键空间。

上传初始化现在也将请求指纹与结果原子保存，覆盖秒传和普通传输。指纹包含名称、MIME、大小、SHA、传输模式及目标目录；同键改变这些参数会冲突。并发初始化会串行检查活跃会话数与配额预留，避免并发超额预留。客户端重连后使用原键获得原会话和刷新的缺失分片地址；完成的秒传重试返回原节点，不重复扣配额。

`batch_nodes` 每批最多 100 个节点，操作为 `move`、`copy`、`trash` 或 `restore`。使用 ID 或路径选择源，恢复操作使用 ID。示例：

```json
{
  "operation":"move",
  "paths":["/inbox/a.txt","/inbox/b.txt"],
  "target_path":"/reports/2026",
  "dry_run":true
}
```

返回 `items` 中每项的 `source`、`success`、成功节点或 `error_code/error`，以及 `succeeded/failed` 总数。单项失败回滚该项，其余项继续；真实执行的所有成功项和整批响应一起提交。旧的 `*_nodes` 工具保留整体成功/失败语义，需要逐项反馈时使用 `batch_nodes`。

`dry_run:true` 会运行相同的数据库校验和变更逻辑，再回滚整个事务，不创建幂等记录。预览按给定顺序考虑同批前项的影响；复制预览不返回可用的新节点 ID。预览不会锁定未来状态，正式执行时仍可能因配额、同名冲突或其他客户端修改而失败。正式执行使用 `dry_run:false` 和新的逻辑操作键即可。

真实批次重试会返回原整批结果，包括其中失败的项。只重试失败项时，提交这些项并使用新幂等键。批量输入不要重复节点，也应避免同时选择目录及其子节点，以免按顺序执行后发生冲突。

## 上传

Agent 调用 `prepare_upload`：

```json
{
  "parent_id": "019f...",
  "name": "report.pdf",
  "size": 8388608,
  "content_type": "application/pdf",
  "sha256": "可选的 64 位小写十六进制",
  "transport": "auto",
  "idempotency_key": "agent-job-20260713-001"
}
```

`transport` 可取：

- `auto`：64 MiB 及以下使用 `single_put`，更大文件使用 `multipart`。
- `single_put`：返回一个 PUT URL，失败时整文件重传。
- `multipart`：返回 16 MiB 分片 URL，支持查询已传分片和刷新 URL。

如果 SHA-256 命中已验证 blob，响应直接是：

```json
{
  "mode": "instant",
  "status": "ready",
  "node": { "id": "019f...", "name": "report.pdf", "kind": "file" }
}
```

未命中或未提供 SHA-256 时，单 PUT 响应包含：

```json
{
  "mode": "single_put",
  "upload_id": "019f...",
  "status": "uploading",
  "put_url": "https://s3.example.com/...",
  "expires_at": "2026-07-13T16:00:00Z"
}
```

Agent 使用自己的 HTTP 工具上传，示例：

```bash
curl --fail --request PUT --upload-file ./report.pdf 'PUT_URL'
```

随后调用 `complete_upload({"upload_id":"019f..."})`，并轮询 `get_upload_status`。状态变化为：

```text
uploading -> completing -> finalizing -> processing -> verifying -> ready
                                                    \-> failed
```

也可调用 `wait_upload({"upload_id":"019f...","timeout_seconds":20})`，减少客户端轮询。默认等待 20 秒，可设置 1–25 秒；`ready`、`failed`、`aborted` 会立即返回。等待超时正常返回最新状态，不代表上传失败；非终态时可再次等待。请求取消会停止等待，不会取消上传。

无 SHA 上传写入 `staging/mcp/{user}/{upload_id}`。服务端流式计算 SHA-256，迁移到内容寻址 key，执行最终去重并清理 staging。提供 SHA 时仍会服务端复核；声明错误会进入 `failed`，不创建文件节点。

分片 URL 每次最多返回 100 个。Agent 使用 `get_upload_parts(first_part, limit)` 分页取得 URL，每片除末片外必须严格等于 `part_size`。

## 下载

单文件调用：

```json
{ "node_id": "019f..." }
```

`prepare_download` 返回：

```json
{
  "method": "GET",
  "url": "https://s3.example.com/...",
  "filename": "report.pdf",
  "size": 8388608,
  "content_type": "application/pdf",
  "archive": false,
  "expires_in_seconds": 900
}
```

Agent 使用自己的下载工具保存文件：

```bash
curl --fail --location 'GET_URL' --output ./report.pdf
```

文件夹或批量下载使用 `node_ids`：

```json
{ "node_ids": ["019f...", "019e..."] }
```

响应是 10 分钟有效的受限 ZIP 票据：

```json
{
  "method": "GET",
  "url": "https://pan.example.com/mcp-download/TICKET",
  "filename": "gopan-打包.zip",
  "size": 12345678,
  "content_type": "application/zip",
  "archive": true,
  "expires_in_seconds": 600
}
```

票据只授权其中列出的节点，不能用于 GraphQL、MCP 或其他用户文件。ZIP 复用现有 2 GiB 打包上限和并发限流。

## 安全与清理

- PUT/GET URL 短期有效，object key 由服务端生成，Agent 不能指定。
- 完成上传时校验对象存在、实际大小、目录权限和配额。
- 同一用户最多同时保留 20 个活跃传输会话。
- `idempotency_key` 用于 Agent 重试，避免重复创建上传任务。
- 过期 single PUT、multipart 和 staging 对象由 worker 定期清理。
- API Key 和 OAuth Token 只保存哈希，最后使用时间最多每五分钟写一次。
- OAuth 授权码 5 分钟有效且只能兑换一次；Refresh Token 使用后立即轮换。
- `/mcp` 拒绝不可信的浏览器跨源请求；原生 Agent 的无 `Origin` 请求正常通过。
- 文本读取限制类型、UTF-8 编码和 64 KiB 单次上限。
- 所有节点操作继续使用现有 service，不允许 MCP 直接写数据库。

## 运行与验证

MCP 与主服务一起启动，不需要额外进程。开发环境仍使用：

```bash
make dev-server
```

协议健康检查可发送 MCP initialize：

```bash
curl --fail https://pan.example.com/mcp \
  -H 'Authorization: Bearer gopan_key_xxx' \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  --data '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"smoke","version":"1"}}}'
```
