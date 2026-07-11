# gopan

自部署云盘。Go + GraphQL + Postgres + MinIO 后端,Vue 3 前端,单二进制交付。

当前阶段:**M1~M5 完成,待真机上线打 v1.0.0**(部署走 doc/09 上线手册)。功能:分片直传/秒传/断点续传、图片/音视频/PDF/文本/Office 预览、文件与文件夹分享(密码、有效期、访客只读子树)、打包下载、回收站、双 token 认证。

## 快速开始

```bash
make dev-env      # compose 起 postgres/minio/gotenberg
make dev-server   # 后端 :8080(自动跑迁移)
make dev-web      # 前端 :5173(proxy → 8080)

make gen          # 改了 schema.graphqls / db/queries 后重新生成三件套
make test         # go test + vet(集成测试需 TEST_DB_URL)
make build        # 前端构建 + embed → server/gopan 单二进制
```

生产运行只需要 `server/gopan` 二进制 + `GOPAN_DB_URL` + `GOPAN_JWT_SECRET`(≥32 字节),配置项见 doc/08。

## 设计文档

全部设计文档在 [doc/](./doc/):

| 文档 | 内容 |
|---|---|
| [01 · 需求与范围](./doc/01-需求与范围.md) | v1 功能清单、明确不做的、非功能要求、术语表 |
| [02 · 技术选型](./doc/02-技术选型.md) | 前后端选型表、不用什么及理由 |
| [03 · 架构与核心流程](./doc/03-架构与核心流程.md) | 组件图;上传(秒传/分片/断点)、下载、预览、分享、GC 流程 |
| [04 · 数据模型](./doc/04-数据模型.md) | Postgres 全量 DDL、不变量、容量估算 |
| [05 · GraphQL 设计](./doc/05-GraphQL设计.md) | 完整 SDL(接口唯一事实源)、约定、resolver 要点 |
| [06 · 认证与安全](./doc/06-认证与安全.md) | 双 token 细节、访客授权、预签名边界、限速 |
| [07 · 前端设计](./doc/07-前端设计.md) | 路由/组件/store 划分、上传器状态机、token 处理层 |
| [08 · 项目结构与部署](./doc/08-项目结构与部署.md) | 仓库布局、开发流、compose、运维、里程碑 |
| [09 · 上线手册](./doc/09-上线手册.md) | 从裸机到可用:部署/升级/回滚/备份恢复/排障/配置速查 |

## 实现回顾

每个里程碑实际交付了什么、关键决策、踩坑记录,在 [doc/milestones/](./doc/milestones/):

| 里程碑 | 内容 |
|---|---|
| [M1 · 骨架](./doc/milestones/M1-骨架.md) | 双 token 认证、目录树 CRUD、三条代码生成链、embed 单二进制 |
| [M2 · 传输](./doc/milestones/M2-传输.md) | 分片直传、秒传、断点续传、hash 校验与谎报回收、自研上传器 |
| [M3 · 预览](./doc/milestones/M3-预览.md) | 派生物管线(缩略图/封面/时长/Office 转 PDF)、预览模态 |
| [M4 · 分享](./doc/milestones/M4-分享.md) | 分享链接、访客子树授权、og 落地页、流式 zip 打包 |
| [M5 · 收尾](./doc/milestones/M5-收尾.md) | 欠账清零(回收站自动清理/复制/搜索/配额/改密码)+ Docker 生产化;真机上线后打 v1.0.0 |
| [M6 · 计划](./doc/milestones/M6-计划.md) | **待做**:加固——测试矩阵补齐、/pack 限速、refresh 表清理、深度限制、/metrics、安全头 |

## 读法

想看全貌:01 → 02 → 03。要动手:04、05 是实现的两份合同,08 末尾是里程碑顺序(M1 骨架 → M2 传输 → M3 预览 → M4 分享)。想知道每步实际怎么落地的:doc/milestones/ 按序读。
