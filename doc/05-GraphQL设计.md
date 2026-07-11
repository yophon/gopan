# 05 · GraphQL 设计

前后端的合同。gqlgen 从这份 SDL 生成 Go 骨架,GraphQL Code Generator 从同一份生成前端类型——本文件是唯一事实源,改接口先改这里。

## 约定

- 单端点 `POST /query`;access token 走 `Authorization: Bearer`,由 HTTP 中间件解析进 ctx,resolver 只读 ctx。
- 字节流不过 GraphQL:上传/下载/预览统一返回**预签名 URL 字段**。
- 错误:业务错误用 gqlgen 的 error extensions 带 `code`(如 `QUOTA_EXCEEDED`、`NAME_CONFLICT`、`SHARE_PASSWORD_REQUIRED`),前端按 code 分支;不靠错误文案。
- 列表用游标分页(Relay 风格简化版);目录列表默认 200/页。
- 访客(share scope)只能调 Query,且鉴权层限制在分享子树。
- 深度限制 8、复杂度限制 300(gqlgen 自带),防递归查询打挂。

## SDL

```graphql
scalar Time
scalar Int64

# ---------- 对象 ----------

type User {
  id: ID!
  username: String!
  quotaBytes: Int64!
  usedBytes: Int64!
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

enum TaskStatus { PENDING, RUNNING, DONE, FAILED }

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

## resolver 层要点

- `Node.downloadUrl` / `PreviewInfo` 的预签名生成是纯计算(HMAC),不打 MinIO,列表页 200 个节点无压力;但只在客户端真取该字段时算(GraphQL 天然按需)。
- `children`、`searchNodes` 的 `Node.preview.thumbUrl` 会触发 derivatives 批量查询 → 必须走 dataloader 按 blob_id 合并。
- 访客 token 下,`node`/`children`/`searchNodes` 的入参 node 一律先过 `IsDescendant(shareRoot, id)`;`searchNodes` 范围限分享子树。
- `refresh` 与 `login` 在 resolver 里写 Set-Cookie(httpOnly, Secure, SameSite=Lax, path=/query),GraphQL body 永远不带 refresh token。

## 版本策略

v1 不做 schema 版本化;字段只加不删,废弃用 `@deprecated`。前端 codegen 在 CI 里跑,schema 不兼容改动直接编译失败暴露。
