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

永久删除故意不暴露给第一版 MCP。

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
