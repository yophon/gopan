# M9 · WebDAV

> v2 第三个里程碑(计划见 doc/13)。交付:标准协议入口——应用密码认证、x/net/webdav 适配、流式转存走既有 verify 管线;rclone 真实客户端验收通过。发布 `v1.3.0`,v2 三件套齐,合并发布 **v2.0.0**。

## 交付清单(对照计划,全部落地)

| 项 | 结果 |
|---|---|
| 迁移 `00006` | app_passwords 表(sha256 存哈希、每设备一条、可单独吊销) |
| service/apppass.go | Create(`gopan_` 前缀明文只出一次,上限 20 条)/ List / Revoke / Authenticate(**限速只记失败**,见踩坑 2)|
| internal/dav | x/net/webdav 的 FileSystem 适配:路径→node 解析、读=minio 惰性 Seek 流(Range 白送)、写=io.Pipe 流式转存(16MB 分片攒块)+ Close 定稿;LOCK 用 MemLS |
| CommitStreamed | 与 completeUpload 同构的定稿事务:UpsertBlob(pending)→ 建/换节点 → 引用与配额 → 标脏 → 入队 verify_hash。**信任模型不分叉**:服务端算的 sha 也走同一条校验管线,verify 后自动触发缩略图派生 |
| 语义对齐 | PUT 覆盖=换 blob 指向按差额调配额(不进回收站);DELETE=软删;MKCOL/MOVE 精确名冲突即错;单文件 5GiB 上限(CopyObject 单调用上限) |
| GraphQL + 前端 | appPasswords 三件套;应用密码管理对话框(地址提示、明文一次性展示、吊销即断) |
| 防线 | /dav 指标路径归一;写入并发闸(容量 2 满拒);认证后才豁免 Server 超时 |
| 验证 | dav 包端到端集成测试(httptest 裸打协议方法)+ m9 smoke 入 make smoke + **rclone 真实验收**(copy 上行/lsl/copy 下行逐字节一致/sync 删除,嵌套目录自动 MKCOL) |

## 关键实现

- **内容寻址 vs 流式 PUT 的矛盾**:对象 key 是 sha,但 PUT 时 sha 未知。解法:Write 一边喂 sha256 hasher 一边过 io.Pipe 交给 PutObject(size=-1)写临时 key;Close 时若 blob 已存在直接丢临时对象引用现有(WebDAV 版秒传),否则 CopyObject 到 `blobs/{sha}`。全程零落盘、内存峰值 = 16MB/路 × 并发闸 2。
- **主密码天然进不来**:应用密码存独立表,Basic 里给主密码就是查不到哈希——不需要"禁止主密码"的代码,结构即约束。
- **覆盖不进回收站是刻意的**:rclone sync 每轮覆盖几十个文件,走软删的话回收站秒变垃圾场且配额虚占。换 blob 指向 + 旧 blob ref−1 进 GC 轨道,幂等覆盖(同内容)连引用都不动。
- **协议状态机不自己写**:PROPFIND 的 XML、Depth、Overwrite、锁令牌全部交给 x/net/webdav,适配层只有 500 行,litmus 级的协议细节是标准库背书的。

## 踩坑记录

1. **Server 全局 60s 超时会掐断长请求(v1 潜伏问题)**:http.Server 的 Read/WriteTimeout 是绝对值,大文件 PUT/GET 和分钟级的 /pack 下载都会被拦腰切断——v1 的 smoke 全是小文件,从没暴露。修法:认证/过闸之后用 `http.NewResponseController` 按请求清除 deadline,全局超时继续挡未认证连接。**顺手把 /pack 的同款问题修了**。教训:全局超时对"常规请求"是保护,对"合法长请求"是暗雷,要按请求豁免而不是全局放大。
2. **限速按请求扣令牌,把合法客户端打成 429**:WebDAV 客户端每个请求都带 Basic 重新认证,rclone 一秒几十个请求是正常水位,smoke 跑到第 15 个请求就 429 了。防爆破要限的是**失败尝试**,不是认证次数——成功路径零消耗,失败才扣桶。这个 bug 是 smoke 抓到的,真实客户端(rclone)上必炸。
3. **集成测试包并行共库,互相 DROP SCHEMA**:dav 包和 service 包的测试都指 TEST_DB_URL 并在 setup 里重建 schema,`go test ./...` 按包并行,俩包对撞偶发全红。`-p 1` 串行化并写进 Makefile 注释。单库多包集成测试,并行度是隐式全局状态。

## 验证

- `go test -race -p 1` 全绿:service **75.0%**(≥73% 闸门)、dav 71.0%(新包,协议适配层)。
- 全量 smoke(m2~m9)+ 浏览器探针绿;m9 覆盖认证边界(错密码/主密码/吊销全 401)、PROPFIND/MKCOL/PUT/GET/Range、覆盖语义、MOVE、DELETE 进回收站、GraphQL 侧同树可见。
- **rclone 真实客户端**:configless 环境变量方式接入,copy 上行 → lsl → copy 下行逐字节比对 → sync 删除,全过。
- nginx 模板补 /dav 段(request_buffering off、5g body、3600s 超时)。

## 遗留

- Finder / Windows 资源管理器 / 手机文件 App 的真机手测未做(本机无对应环境),挂载指引已写进 doc/11;协议层有 x/net/webdav 与 rclone 双背书,风险可控。下次真机部署时按 doc/13 的三客户端清单过一遍。
- 大于 5GiB 的 WebDAV 上传不支持(记录在使用手册),让位给 Web 端分片直传。
- v2 收官:M7/M8/M9 全部交付,v2.0.0 发布;M10(预览预加载、PWA)按计划为机动项,转入日常迭代不阻塞版本。
