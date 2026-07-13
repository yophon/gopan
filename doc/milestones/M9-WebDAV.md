# M9 · WebDAV(计划)

> 状态:进行中。目标见 doc/13 第六节:标准协议入口,Finder / rclone / 手机文件 App 三场景跑通。9a 只读、9b 读写分段交付,合并发布 v1.3.0。

## 设计决策(开工前定死)

1. **应用密码,不用主密码**:`app_passwords` 表(随机 token 只显示一次、存 sha256、每设备一条、可单独吊销)。高熵随机 token 用 sha256 查表即可,不需要 bcrypt 慢哈希——与 refresh token 同构。密码带 `gopan_` 前缀便于识别与泄露扫描。主密码天然进不来(不在 app_passwords 表里)。
2. **库**:适配 `golang.org/x/net/webdav` 的 FileSystem/File 接口,LOCK 用自带 MemLS(单机)。协议状态机(PROPFIND XML、Depth、Overwrite、锁令牌)交给标准库,我们只做树与字节的映射。
3. **写入字节流过 Go(显式例外)**:PUT 无预签名余地。管道流式转存:Write → sha256 hasher + io.Pipe → minio PutObject(size=-1,**PartSize 压到 16MB**——默认 128MB/并发连接会把 2G 小机内存打爆)到临时 key;Close 时定稿。全局并发闸(容量 2,同 /pack 思路,满拒不排队)。
4. **dav 上传走既有 verify 管线**:定稿 = 临时对象 CopyObject 到 `blobs/{sha}`(sha 已流式算出)+ 与 completeUpload 同构的单事务(UpsertBlob pending → 节点建/换 → ref+1 → 配额 → 标脏)→ 入队 verify_hash。不走"服务端算的就直接 verified"捷径:信任模型保持单一,verify 后自动触发派生物(缩略图),一条管线不分叉。
5. **WebDAV 语义与网盘语义的对齐**:PUT 覆盖已有文件 = 换 node 的 blob 指向(旧 blob ref−1、配额差额调整),**不产生回收站副本**——rclone sync 会高频覆盖,进回收站就是垃圾制造机;DELETE = 软删进回收站(误删可救,与 Web 端一致);MKCOL/MOVE 要求精确名,冲突报 405/412,不做 Web 端的自动改名(协议语义优先)。
6. **单文件上限 5GiB**(CopyObject 单调用上限),超出返回错误——WebDAV 场景的大文件让位给 Web 端分片直传,不为长尾上 ComposeObject。

## 交付清单

- 迁移 `00006`:app_passwords 表。
- service/apppass.go:Create(生成一次性明文)/ List / Revoke / Authenticate(限速防爆破;禁用用户拒);GraphQL `appPasswords` / `createAppPassword` / `revokeAppPassword`。
- internal/dav:FileSystem 适配(路径→node 树解析、FileInfo、读=minio Seek 流、写=管道转存)、Basic 认证中间件、挂载 `/dav/`。
- 前端:应用密码管理对话框(生成/列表/吊销,明文一次性展示 + WebDAV 地址提示)。
- metrics:`/dav/*` 路径归一(基数防线)。
- smoke `m9_webdav.py`:裸 HTTP 实现 PROPFIND/GET/PUT/MKCOL/MOVE/DELETE 断言;错密码 401、吊销后 401、**主密码 401**;PUT 后 GraphQL 侧可见、DELETE 后进回收站。
- doc/09(挂载方式)、doc/11(怎么用)、doc/12(能力表)同步。

## 验收场景

- rclone:ls / copy / sync 双向。
- Finder:挂载浏览下载;写入(考验 LOCK)。
- 手机文件 App(Documents 等):浏览、下载、上传。
- 反代注意:nginx 需放行 PROPFIND 等扩展方法与 Destination 头,模板同步更新。
