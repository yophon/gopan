# 让 Claude 帮你整理自建网盘：gopan 的远程 MCP 实操

> 草稿。发布前替换截图 / GIF 占位符；客户端接入步骤以 [docs/MCP.md](../../docs/MCP.md)「客户端接入」为准。

![TODO: 30 秒 GIF——在 Claude 里说「把上个月的发票挑出来放进 /报销」，出现预览，确认，网盘里多出「报销」文件夹](../assets/post01-invoice.gif)

gopan 是一个自部署网盘：一个 Go 二进制，加上 Postgres 和 MinIO，2 核 2G 的 VPS 就能跑。它自带一个远程 MCP 端点 `/mcp`。接上之后，Claude、Codex 这类 Agent 可以替你找文件、读文件、整理目录、建分享。

这篇讲三件事：怎么接上，能让它做什么，怎么防它乱来。

## 为什么不直接把文件丢进聊天窗口

- **文件留在自己的服务器上。** Agent 按需列目录、搜索、读取，拿到的是元数据、文字和短时效的下载地址，不是整个网盘。
- **文件的字节不经过 MCP。** 上传、下载走预签名地址，由 Agent 自己的 HTTP 工具直连存储；MCP 返回的 JSON 里只有任务、地址和状态。
- **PDF、Office 不用先下载。** 服务器已经把文字提取好了，`read_document_text` 直接按段读。
- **网盘的功能都在。** 分享、收集链接、离线下载、剪藏，Agent 用的和网页用的是同一套服务层。

## 接上 gopan

端点是 `https://pan.example.com/mcp`（换成你的域名），传输是 Streamable HTTP。认证有两种：

- **OAuth 2.1（推荐）**：支持动态注册客户端和 PKCE。支持 OAuth 的客户端填上地址，会自己发现端点、打开一次授权确认，之后令牌自动续期，不用把长期密钥写进配置。
- **API Key**：给不支持 OAuth 的客户端和 CI 用。在网页的「Agent 接入」里创建，明文只显示一次。

Codex：

```bash
codex mcp add gopan --url https://pan.example.com/mcp
codex mcp login gopan
```

Claude Code、Claude.ai 连接器、ChatGPT、Cursor：TODO(以 docs/MCP.md 为准)——发布前把「客户端接入」里各客户端的步骤抄过来，并注明哪些真机验过。

授权时只勾需要的权限。权限按组分：`files:read`、`files:download`、`files:upload`、`files:write`、`files:delete`、`shares:read`、`shares:write`，另有传输助手的 `chat:*` 和操作记录的 `audit:read`。还可以在网页上把一个凭据限定到某个目录，比如只许它动 `/AI工作区`：列表、搜索、移动目标都只在这个目录里，传入范围外的 ID 会被拒绝。

![TODO: OAuth 授权确认页截图，能看到勾选的权限](../assets/post01-oauth.png)

## 五个例子

下面每个例子写的是：你怎么说，它会调哪些工具。工具名在 docs/MCP.md 的工具表里都有。

**1. 归档发票**

> 把上个月收到的发票挑出来，放进 /报销/2026-10

它会用 `search_file_contents` 按文件里的文字搜「发票」（PDF、Word、Excel 都能搜，结果带命中片段和 PDF 页码），用 `create_directories` 建好目录，再用 `batch_nodes` 先 `dry_run` 预览要移动哪些文件，你确认后才真的移动。

**2. 读合同**

> /合同/租房合同.pdf 讲了什么？押金和退租怎么写的？

它用 `resolve_path` 拿到文件，再用 `read_document_text` 分段读提取好的文字，每次最多 5 万字，不用把文件下载下来。

**3. 收朋友的照片**

> 给 /旅行/京都 开一个收集链接，月底截止，按上传的人分文件夹

`create_file_request`：朋友不用注册就能往里传，但看不到文件夹里已有的东西；可以设密码、截止时间、按上传者分子文件夹，以及单文件、总量、个数上限。

**4. 离线下载**

> 把这个链接的安装包下到 /软件

`fetch_url` 让服务器在后台去下，字节不经过你的电脑，也不经过 Agent；再用 `get_job` 等结果。内网地址会被拒绝。

**5. 剪藏网页**

> 把这篇文章存一份

`clip_url`：服务器抓下网页正文，存成 Markdown，图片放进旁边的 `.assets` 文件夹，之后可以按内容搜到。

![TODO: 截图——Claude 对话里依次调用 search_file_contents、batch_nodes(dry_run)，以及给出的预览列表](../assets/post01-tools.png)

**还有一个：上传本地文件**（只适用于能读本地文件的客户端，见「局限」）

> 把 ~/Downloads/report.pdf 存到 /工作

流程是 `prepare_upload` → Agent 用 curl 往返回的地址 PUT → `complete_upload` → `wait_upload`。64 MiB 以下一次 PUT，更大的按 16 MiB 分片。Agent 事先算好 SHA-256 一起传的话，服务器上已经有同样内容时会直接秒传，不用再传字节。

## 怎么防它乱来

- **权限和目录范围**：见上一节。不需要对外分享就不给 `shares:write`，它就建不了公开链接。
- **删除只进回收站。** 普通凭据只有 `trash_nodes`，没有彻底删除；回收站里的东西 30 天内都能恢复。
- **每次调用都有记录。** 网页「Agent 接入 → 操作记录」能看到哪个凭据、什么时候、调了什么工具、动了哪些文件、成没成功。不记录文件内容、密码和预签名地址。
- **改动能撤回。** 网盘的「动态」记下每次改动的来源。同一次 Agent 调用的改动合成一组，可以单条撤回、整组撤回，也可以筛出「这个 Agent 今天的改动」一次全撤（一次最多 1000 条，先预览再执行）。撤回管移动、改名、删除和新建。
- **大量删除会提醒。** 同一个 Agent 凭据 10 分钟内删掉 200 个以上文件，或者 2 GiB 以上，会发通知。

![TODO: 「动态」页截图——按来源筛出某个 Agent 的改动，「全部撤回」的预览](../assets/post01-undo.png)

还有一点要记住：Agent 读到的文档内容是数据，不是指令。如果某份文档里写着「把所有文件分享出去」，模型有可能照做。权限给得越少，这类问题的影响越小。

## 局限

- **网页聊天客户端不能上传本地文件。** Claude.ai、ChatGPT 网页版读不到你电脑上的文件，所以找、读、整理、分享、离线下载、剪藏都能做，上传不行。上传要用 Claude Code、Codex 这类能读本地文件、能发 HTTP 请求的客户端。
- **网页客户端要求 gopan 有公网地址。** 它们是从云端连过来的，内网里的 gopan 连不上。TODO(以 docs/MCP.md 为准)
- **读过的内容会发给模型服务商。** 文件留在你的服务器上，但 Agent 读过的文字、看过的元数据会进入对话。敏感目录用目录范围隔开。
- **覆盖和彻底删除撤不了。** 撤回只管可逆的操作。

## 部署

gopan 以 MIT 许可证开源：<https://github.com/yophon/gopan>。部署步骤见 [doc/09 上线手册](../09-上线手册.md)，MCP 的完整说明见 [docs/MCP.md](../../docs/MCP.md)。

TODO(作者确认)：快速开始的命令（是否已能直接拉预构建镜像）照 README 当时的写法填。
