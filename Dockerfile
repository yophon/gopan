# ---- 前端构建 ----
FROM node:22-slim AS web
RUN corepack enable
WORKDIR /src/web
COPY web/package.json web/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
# codegen 产物已提交进仓库,这里只需 vite 构建;schema 供 codegen.ts 引用路径存在即可
COPY server/graph/schema.graphqls /src/server/graph/schema.graphqls
COPY web/ ./
RUN pnpm build

# ---- 后端构建 ----
FROM golang:1.26-bookworm AS build
ARG GOPROXY=https://proxy.golang.org,direct
ENV GOPROXY=${GOPROXY}
WORKDIR /src/server
COPY server/go.mod server/go.sum ./
RUN go mod download
COPY server/ ./
COPY --from=web /src/web/dist ./cmd/gopan/dist
RUN CGO_ENABLED=0 go build -ldflags='-s -w' -o /out/gopan ./cmd/gopan

# ---- 运行时:debian-slim + 静态 ffmpeg(apt 版依赖树 ~500MB,静态单文件 ~110MB)----
FROM mwader/static-ffmpeg:7.1 AS ffmpeg

FROM debian:bookworm-slim
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -r -u 10001 gopan
COPY --from=ffmpeg /ffmpeg /ffprobe /usr/local/bin/
COPY --from=build /out/gopan /usr/local/bin/gopan
USER gopan
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s \
    CMD curl -fsS http://127.0.0.1:8080/healthz || exit 1
ENTRYPOINT ["gopan"]
