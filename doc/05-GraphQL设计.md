# 05 · GraphQL 设计

前后端的合同。gqlgen 和 GraphQL Code Generator 都读取 `server/graph/schema.graphqls`,它才是唯一可执行事实源;本篇记录设计约定与核心 SDL 摘要。改接口先改 schema,再跑 `make gen`。

## 约定

- 单端点 `POST /query`;access token 走 `Authorization: Bearer`,由 HTTP 中间件解析进 ctx,resolver 只读 ctx。
- 字节流不过 GraphQL:浏览器上传/下载/预览统一返回**预签名 URL 字段**。Agent 文件传输走独立 `/mcp`,同样只返回 URL/状态。
- 错误:业务错误用 gqlgen 的 error extensions 带 `code`(如 `QUOTA_EXCEEDED`、`NAME_CONFLICT`、`SHARE_PASSWORD_REQUIRED`),前端按 code 分支;不靠错误文案。
- 列表用游标分页(Relay 风格简化版);目录列表默认 200/页。
- 访客(share scope)只能调 Query,且鉴权层限制在分享子树。
- 深度限制 8、复杂度限制 300,防递归查询打挂。(复杂度限制 gqlgen 自带;深度限制 gqlgen 没有,M6 自研 AST 计算实现——命名 fragment 按定义展开、seen 集防循环。)

## 核心 SDL 摘要

```graphql
scalar Time
scalar Int64

# ---------- 对象 ----------

type User {
  id: ID!
  username: String!
  quotaBytes: Int64!
  usedBytes: Int64!
  isAdmin: Boolean!
}

enum NodeKind { FILE, FOLDER }

type Node {
  id: ID!
  parentId: ID
  name: String!
  kind: NodeKind!
  size: Int64          # 文件 = blob 大小;文件夹 = null
  mime: String
  sha256: String
  createdAt: Time!
  updatedAt: Time!
  deletedAt: Time
  preview: PreviewInfo!         # 该文件可用的预览方式与产物
  downloadUrl: String           # 预签名 GET,15min;文件夹为 null
  subtreeBytes: Int64           # 文件夹异步统计
  subtreeCount: Int64
  statsStale: Boolean
}

enum PreviewKind { NONE, IMAGE, PDF, NATIVE_PDF, VIDEO, AUDIO, TEXT, OFFICE }

type PreviewInfo {
  kind: PreviewKind!
  thumbUrl: String              # thumb256,列表格子
  largeUrl: String              # thumb2048 / cover / 原图
  contentUrl: String            # 播放地址 / PDF 地址 / 文本原文地址
  status: TaskStatus            # OFFICE 转换中为 PENDING/RUNNING
  durationSec: Int              # 音视频
}

enum TaskStatus { PENDING, RUNNING, DONE, FAILED, UNAVAILABLE }

type NodePage {
  items: [Node!]!
  nextCursor: String            # null = 没有下一页
  total: Int!
}

type UploadInit {
  instant: Boolean!             # 秒传命中
  node: Node                    # instant = true 时返回
  session: UploadSession        # instant = false 时返回
}

type UploadSession {
  id: ID!
  partSize: Int!
  partUrls: [PartUrl!]!         # 未完成分片的预签名 PUT
  uploadedParts: [Int!]!        # 断点续传:已完成分片号
  expiresAt: Time!
  status: String!
}

type PartUrl { partNumber: Int!, url: String! }

type Share {
  id: ID!
  token: String!
  node: Node!
  hasPassword: Boolean!
  expiresAt: Time
  createdAt: Time!
}

type ShareInfo {                # 公开查询,验密前可见的最小信息
  token: String!
  name: String!
  kind: NodeKind!
  needPassword: Boolean!
  expired: Boolean!
}

type AuthPayload {
  accessToken: String!          # JWT 15min;refresh 走 httpOnly cookie,不出现在 body
  user: User!
}

type ShareAuth {                # 访客凭证:不带 User,避免向访客泄露属主信息
  accessToken: String!          # scope=share:{id},30min,无 refresh
}

# ---------- 查询 ----------

enum NodeOrder { NAME, SIZE, UPDATED_AT }

type Query {
  me: User!
  node(id: ID!): Node!
  children(parentId: ID, cursor: String, order: NodeOrder = NAME, desc: Boolean = false): NodePage!
  searchNodes(q: String!, cursor: String): NodePage!
  trash(cursor: String): NodePage!
  myShares: [Share!]!
  uploadSession(id: ID!): UploadSession!      # 断点续传入口
  shareInfo(token: String!): ShareInfo!        # 公开
  # 访客视角(share scope token 调用,root 即分享根)
  shareRoot: Node!
}

# ---------- 变更 ----------

type Mutation {
  register(username: String!, password: String!): AuthPayload!
  login(username: String!, password: String!): AuthPayload!
  refresh: AuthPayload!                        # 凭 cookie 里的 refresh 旋转
  logout: Boolean!                             # 吊销当前 refresh family

  createFolder(parentId: ID, name: String!): Node!
  renameNode(id: ID!, name: String!): Node!
  moveNodes(ids: [ID!]!, targetParentId: ID): [Node!]!
  deleteNodes(ids: [ID!]!): Boolean!           # 进回收站
  restoreNodes(ids: [ID!]!): [Node!]!
  purgeNodes(ids: [ID!]!): Boolean!            # 彻底删除,退配额
  purgeTrash: Boolean!

  initUpload(parentId: ID, name: String!, sha256: String!, size: Int64!): UploadInit!
  completeUpload(sessionId: ID!, etags: [PartEtag!]!): Node!
  abortUpload(sessionId: ID!): Boolean!

  requestPreview(nodeId: ID!): PreviewInfo!    # OFFICE 惰性触发转换,之后轮询 node.preview

  createShare(nodeId: ID!, password: String, expiresAt: Time): Share!
  revokeShare(id: ID!): Boolean!
  verifySharePassword(token: String!, password: String!): ShareAuth!  # 返回访客 JWT
}

input PartEtag { partNumber: Int!, etag: String! }
```

## v2.1 凭据与 OAuth 控制面

MCP tool 不塞进 GraphQL schema;`/mcp` 由 MCP SDK 暴露。GraphQL 只负责已登录用户的 API Key 管理、OAuth consent 决策和授权撤销:

```graphql
type MCPAPIKey {
  id: ID!
  name: String!
  scopes: [String!]!
  createdAt: Time!
  lastUsedAt: Time
}
type MCPAPIKeyCreated { token: String!, credential: MCPAPIKey! }

type OAuthGrant {
  id: ID!
  clientId: String!
  clientName: String!
  scopes: [String!]!
  createdAt: Time!
  updatedAt: Time!
}
type OAuthAuthorizationRequest { clientName: String!, scopes: [String!]! }
type OAuthAuthorizationResult { redirectUrl: String! }

input OAuthAuthorizationInput {
  clientId: String!
  redirectUri: String!
  responseType: String!
  scope: String
  state: String
  codeChallenge: String!
  codeChallengeMethod: String!
}

extend type Query {
  mcpAPIKeys: [MCPAPIKey!]!
  oauthGrants: [OAuthGrant!]!
  oauthAuthorizationRequest(input: OAuthAuthorizationInput!): OAuthAuthorizationRequest!
}
extend type Mutation {
  createMCPAPIKey(name: String!, scopes: [String!]!): MCPAPIKeyCreated!
  revokeMCPAPIKey(id: ID!): Boolean!
  decideOAuthAuthorization(input: OAuthAuthorizationInput!, approved: Boolean!): OAuthAuthorizationResult!
  revokeOAuthGrant(id: ID!): Boolean!
}
```

`createMCPAPIKey` 的明文只在本次响应出现。`oauthAuthorizationRequest` 与 `decideOAuthAuthorization` 都要求正常用户 JWT;未认证 Agent 不能借 GraphQL 给自己签发凭据。协议发现、DCR 和 token 兑换分别走 `/.well-known/*`、`/oauth/register`、`/oauth/authorize`、`/oauth/token`。

## resolver 层要点

- `Node.downloadUrl` / `PreviewInfo` 的预签名生成是纯计算(HMAC),不打 MinIO,列表页 200 个节点无压力;但只在客户端真取该字段时算(GraphQL 天然按需)。
- `children`、`searchNodes` 的 `Node.preview.thumbUrl` 会触发 derivatives 批量查询 → 必须走 dataloader 按 blob_id 合并。
- 访客 token 下,`node`/`children`/`searchNodes` 的入参 node 一律先过 `IsDescendant(shareRoot, id)`;`searchNodes` 范围限分享子树。
- `refresh` 与 `login` 在 resolver 里写 Set-Cookie(httpOnly, Secure, SameSite=Lax, path=/query),GraphQL body 永远不带 refresh token。

## 版本策略

v1 不做 schema 版本化;字段只加不删,废弃用 `@deprecated`。前端 codegen 在 CI 里跑,schema 不兼容改动直接编译失败暴露。
