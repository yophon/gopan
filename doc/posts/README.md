# posts · 推广草稿

这里放给外部看的文章与提交文案，都是**草稿**，不是设计文档。发布前要过一遍下面的前置清单，替换掉所有占位符。

## 各篇发哪里

| 文件 | 是什么 | 发哪里 | 时机 |
|---|---|---|---|
| [01-让Claude帮你整理自建网盘.md](./01-让Claude帮你整理自建网盘.md) | 实操：把 gopan 的远程 MCP 接进 Claude / Codex，能做什么、怎么防它乱来 | 自己的博客（留着带搜索流量），再转 V2EX「分享创造」、linux.do | MCP 登记到各目录之后 |
| [02-2G内存VPS跑完整网盘.md](./02-2G内存VPS跑完整网盘.md) | 技术：小机器上的架构取舍——字节不过后端、内容寻址、同源脚本的坑、备份演练 | 博客、掘金或知乎 | 01 之后 |
| [02-2G-RAM-VPS-full-cloud-drive.en.md](./02-2G-RAM-VPS-full-cloud-drive.en.md) | 02 的英文精简版 | r/selfhosted、Show HN | 中文版之后；README 有英文版、镜像能直接拉之后 |
| [listings.md](./listings.md) | awesome-mcp-servers、mcp.so、魔搭 MCP 广场的条目，V2EX 与 linux.do 的帖子 | 各目录与社区 | 和对应文章同步 |

## 前置（没做完不发）

流量来了卡在部署上，比没有流量更糟。

- [ ] README：版本、文档索引、截图更新，有英文版（#1）
- [ ] compose 里的 MinIO 镜像能匿名拉到（#2）
- [ ] gopan 镜像推到公开的镜像仓库，新用户不用在机上构建
- [ ] docs/MCP.md「客户端接入」写好：Claude Code、Claude.ai 连接器、ChatGPT、Cursor 等各一段，并标明哪些真机验过
- [ ] 截图与 GIF 拍好，放进 `doc/assets/`（见各篇占位符）
- [ ] 文中标 `TODO(作者确认)` 的事实逐条核对

## 占位符约定

- 截图 / GIF：`![TODO: 拍什么](../assets/xxx.png)`。图片放 `doc/assets/`，文件名照占位符里的写。拍图用演示账号和样例文件，不要露出真实文件名。
- `TODO(作者确认)`：拿不准的事实，发布前核对或删掉。
- `TODO(以 docs/MCP.md 为准)`：客户端接入步骤，从 docs/MCP.md 抄过来，不要在文章里另写一套。
- `pan.example.com`：示例域名，照抄 docs/MCP.md 的写法，不换成真实实例。

## 写法

照仓库文档的中文风格：句子短，少形容词，数字要有出处（文中注明来自哪篇 doc）。不写「赋能」「一站式」这类词。每篇都写清楚局限和不做的事。
