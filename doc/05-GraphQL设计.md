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
  path: String                  # 从根开始的路径(v3.8,搜索结果显示「位置」);访客与回收站里为 null,每个节点一次递归查询
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
  expiresAt: Time               # 以下三项 v3.9 加,访客页头部用;有密码的分享验密前一律 null
  size: Int64                   # 文件是大小,文件夹是子树体积
  fileCount: Int64              # 文件夹的文件数(异步统计)
}

type AuthPayload {
  accessToken: String!          # JWT,默认 4 小时;refresh 走 httpOnly cookie,不出现在 body
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
  # 后台校验期间返回 UPLOAD_PROCESSING;重试同一会话,成功后幂等返回节点。
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

## 传输助手(v2.2 建立,v2.5 整改)

单用户单会话。ChatMessage 的 `node` 是附件,惰性解析:

```graphql
type ChatMessage {
  id: ID!
  body: String!
  createdAt: Time!
  source: String!   # 写入渠道:web / mcp / webdav,只做展示
  node: Node        # 附件;无附件为 null;文件被删/彻删后仍为消息,此字段返回 null
}

extend type Query {
  # 最近 limit 条,升序(旧→新)。before:只取更旧的(从锚点往前数 limit 条);after:只取更新的(从锚点往后数 limit 条);
  # 两者互斥。query:正文或附件名 ILIKE 关键词(忽略游标,取最新 limit 条命中后升序返回)
  chatMessages(limit: Int = 200, before: ID, after: ID, query: String): [ChatMessage!]!
  chatFolder: Node                                # 当前指针;从未创建返回 null,不触发创建
}
extend type Mutation {
  sendChatMessage(body: String, nodeId: ID): ChatMessage!  # 至少其一;同一节点幂等
  deleteChatMessage(id: ID!): Boolean!                     # 只删消息,附件文件不动
  ensureChatFolder: Node!                                  # 幂等创建传输助手文件夹
}
```

- **分页**:`before` 是 uuid v7 消息 id,服务层 `ListRecent(last)` 取 `id < last` 的 `limit` 条;前端滚到顶部按 100 条一页往上翻。`after` 走 `ListAfter`,取 `id > after` 升序的前 `limit` 条,给搜索命中后"跳到上下文"用(前端拼 before 50 + 命中 + after 50 的窗口,再往下按 after 翻直到追上最新)。两个游标同时给 `INVALID_INPUT`。`query` 非空时走 `Chats.Search`,`%` `_` `\\` 先转义,按字面匹配。
- **文件进会话由服务端负责**:任何渠道(浏览器分片、秒传、WebDAV PUT、Agent 传输)在 `CreateNode` 之后调用 `attachChatMessage`,parent 等于 `users.chat_folder_id` 时在同一事务里插一条文件消息;指针为空、parent 不匹配、该节点已有消息都是 no-op。前端上传完成只 `invalidateQueries`,不再补发 `sendChatMessage(nodeId)`。覆盖写(WebDAV 同名 PUT)走 `ReplaceNodeBlob`,不新建节点也就不重复落消息。
- `sendChatMessage` 走 `UserFrom`(拒绝 guest);`nodeId` 必须是当前用户的活跃节点,否则 `NOT_FOUND`(防探测,服务层 `GetActiveNodeOwned` 单事务校验)。同一节点已在会话里(自动落的或之前发过的)直接返回那条,不重复插。
- `deleteChatMessage` 只删 `chat_messages` 行;不存在/非本人 `NOT_FOUND`。MCP 不暴露删除。
- **来源**:`service.WithChatSource(ctx, …)` 由 WebDAV 与 MCP 的入口中间件注入,GraphQL 路径不注入即默认 `web`;SendText / SendNode / attachChatMessage 都从 context 取,落到 `chat_messages.source`(迁移 00019,默认 `'web'`,存量行即 web)。
- `ChatMessage.node` resolver 捕获 `service.ErrNotFound` 返回 `(nil,nil)`——文件删除不传染消息读取,前端展示「文件已不存在」态。
- 附件落点:网盘根目录固定文件夹(数据库里仍叫「我的设备」,界面统称传输助手),`ensureChatFolder` 用 advisory lock 保证并发只建一个;指针在 `users.chat_folder_id`,改名/移动不失效。

## 收集文件(v3.1)

属主对文件夹开收集链接,访客不登录往里传,只写不读。设计与取舍见 doc/20 第二节。

```graphql
type FileRequest { id, token, folder: Node!, title, note, hasPassword, requireName, groupByUploader,
                   maxFileSize: Int64, maxTotalBytes: Int64, maxFiles: Int, expiresAt: Time, createdAt,
                   fileCount, totalBytes, uploaderCount, newCount, lastUploadAt }   # 统计按收件记录
type FileRequestUpload { id, fileName, size, uploaderName, ip, userAgent, createdAt, node: Node }  # 文件已删则 node 为 null
input FileRequestInput { title!, note, requireName, groupByUploader, maxFileSize, maxTotalBytes, maxFiles, expiresAt }
type FileRequestInfo { title, note, needPassword, needName, maxFileSize, expiresAt, closed }      # 公开,不含属主信息

extend type Query {
  myFileRequests: [FileRequest!]!                                  # 属主
  fileRequestUploads(id: ID!, cursor: String): FileRequestUploadPage!
  fileRequestInfo(token: String!): FileRequestInfo!                # 公开
  collectUploadSession(id: ID!): UploadSession!                    # 访客
  collectMyUploads: [CollectUpload!]!
}
extend type Mutation {
  createFileRequest(folderId: ID!, input: FileRequestInput!, password: String): FileRequest!
  updateFileRequest(id: ID!, input: FileRequestInput!): FileRequest!   # 整表单替换;密码与文件夹不能改
  revokeFileRequest(id: ID!): Boolean!
  markFileRequestSeen(id: ID!): Boolean!
  openFileRequest(token: String!, password: String, uploaderName: String): CollectAuth!   # 公开
  collectInitUpload(name: String!, size: Int64!): UploadSession!                          # 访客
  collectCompleteUpload(sessionId: ID!, etags: [PartEtag!]!): CollectUpload!
  collectAbortUpload(sessionId: ID!): Boolean!
}
```

- 访客侧是**另一组** mutation,不复用 `initUpload` 等四个:那四个继续只认登录用户(`UserFrom`),访客令牌碰不到。
- 上传协议与登录用户相同(分片预签名 → PUT → complete 轮询 `UPLOAD_PROCESSING`),差别:不传 sha256、没有 `instant`;complete 只回 `{name, size, createdAt}`,不回 Node。
- 错误码:`COLLECT_CLOSED`(撤销 / 截止 / 文件夹进回收站 / 属主被禁用)、`COLLECT_FULL`(链接额度或属主配额用完,不区分)、`FILE_TOO_LARGE`、`COLLECT_PASSWORD_REQUIRED`、`BAD_COLLECT_PASSWORD`、`COLLECT_NAME_REQUIRED`、`TOO_MANY_SESSIONS`、`RATE_LIMITED`。
- `fileRequestUploads` 的 cursor 是上一页最后一条的 id(bigserial),每页 50 条。

## 内容搜索(v3.2)

```graphql
type ContentHit { node: Node!, snippet: String!, highlights: [TextRange!]!, page: Int }   # page 仅 PDF
type TextRange { start: Int!, length: Int! }                                              # 按字符(码点)
type ContentHitPage { items: [ContentHit!]!, nextCursor: String }                         # 不给 total
extend type Query { searchContent(q: String!, cursor: String): ContentHitPage! }
type AdminOverview { …, contentIndex: ContentIndexStats! }   # 已索引 / 无文字 / 截断 / 不支持 / 失败 / 待处理 / 文字量
```

- 只给登录用户(`UserFrom`),分享访客没有内容搜索。
- 语义是"文件文字里包含这个关键词":关键词与内容做同一套归一化(NFKC、CJK 之间去掉空白),词项交集取候选,`strpos(lower(content), lower(q))` 复核。关键词切不出词项(单个汉字、一两个字母)回 `QUERY_TOO_SHORT`;`GOPAN_CONTENT_INDEX=false` 回 `CONTENT_SEARCH_DISABLED`。
- 排序与 `searchNodes` 相同(修改时间倒序、id 决胜),游标同一种编码,`o` 为 `CONTENT`;每页 50 条。
- 片段:首处命中前 60 字到后 100 字,两头被截加"…";高亮区间在片段里按码点计,前端按区间切段渲染,不用 `v-html`。

## 后台任务、离线下载与压缩包(v3.3)

```graphql
enum JobKind { FETCH_URL, EXTRACT_ARCHIVE }
enum JobStatus { QUEUED, RUNNING, DONE, FAILED, CANCELED }
type Job { id, kind, status, title, url, parentId, result: Node, bytesDone, bytesTotal, filesDone, filesTotal,
           filesSkipped, errorCode, errorMessage, attempts, source, createdAt, startedAt, finishedAt }
enum ArchiveStatus { PENDING, DONE, TRUNCATED, UNSUPPORTED, FAILED }
type ArchiveInfo { status, format, entryCount, fileCount, totalSize, solid, encrypted, error }
type ArchiveEntry { idx, path, name, isDir, size, modifiedAt, flag, previewable, downloadable }
enum PreviewKind { …, ARCHIVE }

extend type Query {
  myJobs(active: Boolean = false, cursor: String): JobPage!
  job(id: ID!): Job!
  fetchEnabled: Boolean!
  archiveInfo(nodeId: ID!): ArchiveInfo!                                     # 属主,或分享子树内的访客
  archiveEntries(nodeId: ID!, path: String, cursor: String): ArchiveEntryPage!
}
extend type Mutation {
  fetchUrls(urls: [String!]!, parentId: ID): [Job!]!          # 每条链接一个任务
  extractArchive(nodeId: ID!, base: String, paths: [String!], parentId: ID): Job!
  cancelJob(id: ID!): Job!
  retryJob(id: ID!): Job!
  clearFinishedJobs: Int!
}
```

- 除 `archiveInfo` / `archiveEntries` 外都只给登录用户(`UserFrom`);这两个走 `Shares.Authorize`,分享访客在分享子树内能看,收集令牌被拒。
- `fetchUrls` 一次最多 10 条、每人排队 + 进行中最多 10 个(`TOO_MANY_JOBS`);链接不是 http / https 回 `INVALID_URL`;站点关了回 `FETCH_DISABLED`。`source` 按 UA 记 web / android,MCP 记 mcp。
- 任务执行中的错误记在 `errorCode` / `errorMessage`:`FETCH_BLOCKED`(内网、本机、保留地址)、`FETCH_HTTP_STATUS`(4xx)、`FETCH_DNS`、`FETCH_REDIRECTS`、`QUOTA_EXCEEDED`、`FILE_TOO_LARGE`、`ARCHIVE_CORRUPT`、`INTERRUPTED`;网络抖动和源站 5xx 交还队列退避重试(`JOB_ERROR`,30 秒 × 次数,最多 3 次);解压不自动重试(重试会再建一个文件夹)。`DONE` 也可能带 `GOT_HTML`:拿到的是网页,不是文件。
- `myJobs` 新的在前,每页 50,游标 `o` 为 `JOBS`。`archiveEntries` 每页 200,目录在前、按名字,游标 `o` 为 `ARCHIVE`;`previewable` / `downloadable` 由服务端按大小与 `cost` 算。
- `extractArchive` 预检:文件数 ≤ 2 万(`TOO_MANY_FILES`)、解压后总量不超剩余配额(`QUOTA_EXCEEDED`)、选中的里有能解的(`ARCHIVE_NOTHING`)、目录已读完(`ARCHIVE_NOT_READY`)且能打开(`ARCHIVE_UNAVAILABLE`)。`paths` 是包内完整路径、须在 `base` 下;解出的路径相对 `base`。
- 包里的单个文件不走 GraphQL:`GET /arc/{nodeId}/{idx}?token=<access>[&download=1]`(预览地址带不了 Authorization 头,同 `/pack`),错误回 JSON `{code, message}`:415 `UNSUPPORTED_TYPE`、422 `ARCHIVE_ENTRY_TOO_LARGE` / `ARCHIVE_ENTRY_TOO_DEEP` / `ARCHIVE_ENTRY_UNAVAILABLE` / `ARCHIVE_CORRUPT`、409 `ARCHIVE_NOT_READY`、503 `ARCHIVE_BUSY`。

## 影音、通知、直链与图床(v3.4)

```graphql
type PreviewInfo { …, subtitles: [SubtitleTrack!]! }   # 只有 VIDEO 在单节点预览里给
type SubtitleTrack { nodeId, name, label, lang, default }
type Node { …, playProgress: PlayProgress }            # 单节点取,列表里别请求
type PlayProgress { position, duration, finished, updatedAt }
type User { …, unreadNotifications: Int! }             # 随 me 轮询带回
type Notification { id, kind, level, title, body, count, target: NotificationTarget, createdAt, updatedAt, read }
type NotificationTarget { kind: NODE|FILE_REQUEST|LINK|JOBS|SESSIONS|CREDENTIALS|ADMIN, id, parentId }
type NotifyChannel { id, kind: WEBHOOK|NTFY|BARK|TELEGRAM, name, enabled, events, url, topic, hasSecret, secretHint, lastOkAt, lastError, lastErrorAt }
type DirectLink { id, token, path, url, nodeId, name, mime, size, status: ACTIVE|PAUSED|EXPIRED|UNAVAILABLE, pauseReason,
                  source, createdAt, expiresAt, refererHosts, allowNoReferer, dailyBytesCap, autoPause, hasGps,
                  hitsTotal, bytesTotal, lastHitAt, today: LinkUsage!, last30Days: LinkUsage! }

extend type Query {
  playlist(nodeId: ID!): [Node!]!                     # 同文件夹同类,自然顺序;访客在分享范围内可用
  recentPlays(limit: Int = 20): [RecentPlay!]!
  notifications(cursor: String, unreadOnly: Boolean = false): NotificationPage!
  notifyChannels: [NotifyChannel!]!
  notifyEventKinds: [NotifyEventKind!]!
  myLinks(cursor: String): DirectLinkPage!
  link(id: ID!): DirectLink!
  nodeLink(nodeId: ID!): DirectLink
  linkStats(id: ID!, hours: Int = 168): [LinkHourStat!]!
  linkReferers(id: ID!, days: Int = 30): [LinkReferer!]!
  linkHits(id: ID!, cursor: String): LinkHitPage!
  linkPrefs: LinkPrefs!
  linksEnabled: Boolean!
  adminLinkStats: AdminLinkStats!
}
extend type Mutation {
  saveProgress(nodeId: ID!, position: Float!, duration: Float!): PlayProgress   # 不记时返回 null
  clearProgress(nodeId: ID!): Boolean!
  markNotificationsRead(ids: [ID!]): Int!             # ids 为空 = 全部
  clearNotifications: Int!
  createNotifyChannel(input: NotifyChannelInput!): NotifyChannel!
  updateNotifyChannel(id: ID!, input: NotifyChannelInput!): NotifyChannel!   # secret 不传 = 保持,"" = 清除
  deleteNotifyChannel(id: ID!): Boolean!
  testNotifyChannel(id: ID!): Boolean!                # 失败 NOTIFY_FAILED,message 是对方的回应
  createLinks(nodeIds: [ID!]!): [DirectLink!]!        # 幂等:一个文件同时只有一条有效直链
  updateLink(id: ID!, input: DirectLinkInput!): DirectLink!
  pauseLink(id: ID!, paused: Boolean!): DirectLink!
  revokeLink(id: ID!): Boolean!
  setLinkPrefs(input: LinkPrefsInput!): LinkPrefs!
  adminPauseLink(id: ID!, paused: Boolean!): Boolean!
  adminRevokeLink(id: ID!): Boolean!
}
```

- `playlist` 与 `subtitles` 走 `Shares.Authorize`:分享的就是这个视频本身时,访客只拿到它自己、没有字幕(同文件夹的文件不在分享范围里)。`playProgress` 与 `saveProgress` 对访客返回 null。
- `saveProgress` 只记 5 分钟以上的音视频(秒)和 3 页以上的 PDF / Office(页);剩余不到 3% 或不到 20 秒、或翻到最后一页记为看完。
- 通知游标是 id;直链游标 `o` 为 `LINKS`。`notifyEventKinds` 对非管理员不含 `backup`;渠道订阅了管理员事件的非管理员被拒(`INVALID_INPUT`)。渠道最多 10 个(`LIMIT_EXCEEDED`)。
- 直链:只能给文件建(`INVALID_INPUT`);站点关了回 `LINKS_DISABLED`;`dailyBytesCap` 至少 1 MB;`url` 是 `GOPAN_PUBLIC_URL + path`,没配站点地址时等于 `path`。
- 图床密钥是 scope 为 `img:upload` 的 API Key(`createMCPAPIKey(name, ["img:upload"])`),不能和别的 scope 混用(`INVALID_INPUT`)。
- 不走 GraphQL 的三条:
  - `GET /x/{token}/{文件名}`:公开直链,`?s=256|2048` 取图片缩略图;404 不存在或失效、403 来源不允许、429 单 IP 超速、503 暂停 / 超限 / 繁忙(带 `Retry-After`)。
  - `GET /sub/{nodeId}?token=<access>`(或 Bearer 头):字幕转 WebVTT;错误同 `/arc` 的 JSON。
  - `POST /api/img/upload`:`Authorization: Bearer <图床密钥或登录凭证>`,multipart(第一个文件)或原始请求体(`?name=`);成功 `{url, markdown, html, bbcode, name, size, node_id, link_id}`,失败 `{error: {code, message}}`:401、403(密钥没有 img:upload)、415 `UNSUPPORTED_TYPE`、413 `TOO_LARGE`、507 `QUOTA_EXCEEDED`、429 `RATE_LIMITED`、503 `BUSY`。

## 动态、空间、订阅与剪藏(v3.5)

```graphql
type Activity { id, at, actor: ActivityActor!, batchId, action: MKDIR|UPLOAD|OVERWRITE|RENAME|MOVE|COPY|DELETE|RESTORE|PURGE|EXTRACT,
                nodeId, node, nodeKind, name, parentPath, fromPath, toPath, oldName, newName, files, bytes, note,
                undoOf, undoneAt, undoable }
type ActivityActor { kind: WEB|ANDROID|WEBDAV|AGENT|IMG|COLLECT|JOB|FEED|CLIP|SYSTEM, ref, label }
input ActivityFilter { actorKind, actorRef, actions, nodeId, batchId, since, until }
input UndoSelection { ids, batchId, filter }            # 三选一
type UndoResult { done, failed, items: [UndoItem!]! }    # UndoItem { activity, ok, reason }
type SpaceOverview { usedBytes, quotaBytes, trashFiles, trashBytes, byType: [SpaceCategory!]!, duplicateGroups, duplicateBytes }
type SpaceFile { node, path, category: IMAGE|VIDEO|AUDIO|DOCUMENT|ARCHIVE|TEXT|OTHER }
type Feed { id, kind: RSS|GITHUB, url, title, siteUrl, folder, mode: DOWNLOAD|CLIP|NONE, includeWords, excludeWords,
            assetPattern, prerelease, keepLast, intervalMinutes, enabled, lastCheckedAt, lastOkAt, lastError, failCount,
            nextCheckAt, createdAt, itemCount, newCount, latestAt }
type FeedItem { id, title, url, enclosureUrl, enclosureType, enclosureSize, publishedAt, state: NEW|SKIPPED|QUEUED|DONE|FAILED,
                error, node, jobId, createdAt }
type Clip { id, url, title, site, byline, excerpt, publishedAt, node, words, images, readAt, createdAt }
type ClipResult { job: Job, existing: Clip }
type PreviewInfo { …, markdown: Boolean! }             # .md 且 ≤ 2 MB;kind 仍是 TEXT

extend type Query {
  activities(filter: ActivityFilter, cursor: String, limit: Int = 50): ActivityPage!
  activityActors: [ActivityActorSummary!]!
  undoPreview(selection: UndoSelection!): UndoResult!  # 不改任何东西
  spaceOverview: SpaceOverview!
  spaceFolders(parentId: ID): SpaceFolderLevel!
  spaceLargeFiles(category: FileCategory, limit: Int = 100): [SpaceFile!]!
  spaceStaleFiles(days: Int = 365, minSize: Int64 = 104857600, limit: Int = 100): [SpaceFile!]!
  spaceDuplicates(offset: Int = 0, limit: Int = 50): DuplicatePage!
  spaceEmptyFolders(limit: Int = 200): [SpaceFile!]!
  spaceIdle: SpaceIdle!
  adminStorage: AdminStorage!
  feeds: [Feed!]!
  feed(id: ID!): Feed!
  feedItems(feedId: ID!, cursor: String): FeedItemPage!
  previewFeed(url: String!, assetPattern: String, prerelease: Boolean = false): FeedPreview!
  exportOpml: String!
  clips(unreadOnly: Boolean = false, site: String, cursor: String): ClipPage!
  clipSites: [ClipSite!]!
  clip(id: ID!): Clip!
  nodeClip(nodeId: ID!): Clip
  clipPrefs: ClipPrefs!
}
extend type Mutation {
  undoActivities(selection: UndoSelection!): UndoResult!
  createFeed(input: FeedInput!): Feed!                # 先抓一次,地址不对当场报错
  updateFeed(id: ID!, input: FeedInput!): Feed!
  deleteFeed(id: ID!): Boolean!
  refreshFeed(id: ID!): Feed!                          # 同步,最多 20 秒
  fetchFeedItem(itemId: ID!, mode: FeedMode!): FeedItem!
  importOpml(opml: String!): OpmlImportResult!
  clipUrl(url: String!, html: String, force: Boolean = false): ClipResult!
  markClipRead(id: ID!, read: Boolean = true): Clip!
  setClipPrefs(input: ClipPrefsInput!): ClipPrefs!
}
```

- 动态游标 `o` 为 `ACTIVITY`(id 倒序);`filter` 撤回至少给来源、时间或文件(`INVALID_INPUT`),一次最多 1000 条。撤不了的放在 `items[].reason`,不报错。
- 空间分析的清理不另设变更:进回收站用 `deleteNodes`,清空回收站 `purgeTrash`,吊销用 `revokeShare` / `revokeLink` / `revokeFileRequest`。`adminStorage` 只给管理员(`FORBIDDEN`)。
- 订阅:`FEED_EXISTS`(重复)、`TOO_MANY_FEEDS`(100 个)、`FETCH_DISABLED`(本站关了离线下载,订阅与剪藏一起关)、`NOT_A_FEED`、`GITHUB_NOT_FOUND`、`GITHUB_RATE_LIMITED`。GitHub 仓库写 `owner/repo` 或 github.com 链接都行。
- 剪藏:同一个地址剪过、那篇还在时返回 `existing`;`html` 最大 5 MB(`TOO_LARGE`)。剪藏任务是 `Job`(`kind: CLIP_URL`),结果节点是那篇 `.md`。
- 不走 GraphQL 的:
  - `GET /md/{nodeId}?token=<access>[&theme=light|dark][&pt=<px>][&pb=<px>]`:Markdown 渲染成一页 HTML(CSP sandbox、`X-Frame-Options: SAMEORIGIN`);错误也是一页 HTML(401 / 404 / 413 超过 2 MB / 415 不是 Markdown)。`pt` / `pb` 是正文前后多留的空白(0–400 px,站内跳转沿用):App 的阅读页铺满整屏,让出浮在上面的顶栏、下面的底部导航。
  - `GET /md/{nodeId}/f?p=<相对路径>&token=<access>`:那篇里的相对路径,按 .md 所在文件夹解析;图片与文件 302 到签名地址(大图给 2048 缩略图),`.md` 302 到它自己的渲染页。分享访客出不了分享范围。

## 收藏、最近打开与历史版本(v3.10)

```graphql
type Node { …, starred: Boolean!, openedAt: Time }       # 分享访客看到的一律 false / null
type FileVersion { id, size, sha256, mime, savedAt, replacedAt, downloadUrl }
type FileVersionList { items: [FileVersion!]!, keep: Int!, keepDays: Int! }   # keep = 0:本站覆盖时不留旧版本
type SpaceOverview { …, versionFiles: Int64!, versionBytes: Int64! }

extend type Query {
  starredNodes: [Node!]!                              # 最近收藏在前,最多 500;回收站里的不列
  recentFiles(limit: Int = 100): [Node!]!             # 最近打开在前,只有文件
  fileVersions(nodeId: ID!): FileVersionList!         # 最近换下来的在前
}
extend type Mutation {
  setStarred(ids: [ID!]!, starred: Boolean!): [Node!]!
  markOpened(nodeId: ID!): Boolean!                   # 网页 / App 预览、播放、下载时上报
  clearRecent(ids: [ID!]): Boolean!                   # 不给 ids = 全部清掉
  restoreFileVersion(id: ID!): Node!                  # 现在的内容留成新版本
  deleteFileVersions(ids: [ID!]!): Boolean!
  purgeFileVersions(nodeId: ID): Int64!               # 不给 nodeId = 自己所有文件的;返回释放的字节
  initUpload(…, replace: Boolean = false): UploadInit!  # 同名文件就覆盖,旧内容进历史版本
}
```

- 覆盖类动态(`OVERWRITE`)留了版本时 `undoable = true`,撤回 = 恢复那个版本;恢复版本本身也记一条 `OVERWRITE`(`note` 是「恢复到 … 的版本」)。
- `restoreFileVersion`:版本已被清理 `NOT_FOUND`,文件在回收站里 `NODE_DELETED`;`fileVersions` 对文件夹 `NOT_A_FILE`。

## 版本策略

v1 不做 schema 版本化;字段只加不删,废弃用 `@deprecated`。前端 codegen 在 CI 里跑,schema 不兼容改动直接编译失败暴露。
