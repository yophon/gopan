GOPROXY := https://goproxy.cn,direct
export GOPROXY

DEV_DB := postgres://gopan:gopan@127.0.0.1:5433/gopan?sslmode=disable

.PHONY: gen dev-env dev-server dev-web build test smoke lint

# 三个生成器:sqlc、gqlgen、前端 codegen
gen:
	cd server && go tool sqlc generate && go tool gqlgen generate
	cd web && pnpm codegen

dev-env:
	docker compose up -d postgres minio gotenberg

dev-server: dev-env
	cd server && GOPAN_DB_URL='$(DEV_DB)' GOPAN_DEV_MODE=true \
		GOPAN_JWT_SECRET=dev-secret-dev-secret-dev-secret-00 go run ./cmd/gopan

dev-web:
	cd web && pnpm dev

build:
	cd web && pnpm install --frozen-lockfile && pnpm build
	rm -rf server/cmd/gopan/dist && mkdir -p server/cmd/gopan/dist
	cp -r web/dist/* server/cmd/gopan/dist/
	cd server && CGO_ENABLED=0 go build -o gopan ./cmd/gopan
	@echo "==> server/gopan"

# -p 1:集成测试包(service/dav)共用 TEST_DB_URL 指向的库,并行会互相重建 schema
test:
	cd server && go test -p 1 ./... && go vet ./...
	cd web && pnpm test

lint:
	cd server && go vet ./...
	cd web && pnpm typecheck

# 端到端 smoke:需要 dev 依赖与服务已跑(make dev-env && make dev-server)
smoke:
	cd scripts/smoke && python3 m2_transfer.py && python3 m3_preview.py \
		&& python3 m4_share.py && python3 m5_misc.py && python3 m7_admin.py \
		&& python3 m9_webdav.py && node browser_probe.mjs
