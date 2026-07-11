#!/usr/bin/env bash
# gopan 每日备份:pg_dump + MinIO 数据卷打包,保留 7 天。
# cron 示例:0 4 * * * cd /opt/gopan && ./scripts/backup.sh >> /var/log/gopan-backup.log 2>&1
set -euo pipefail

BACKUP_DIR=${BACKUP_DIR:-/var/backups/gopan}
COMPOSE="docker compose -f docker-compose.prod.yml"
STAMP=$(date +%F)

mkdir -p "$BACKUP_DIR"

echo "[$(date '+%F %T')] pg_dump ..."
$COMPOSE exec -T postgres pg_dump -U gopan -Fc gopan > "$BACKUP_DIR/db-$STAMP.dump"

echo "[$(date '+%F %T')] minio volume tar ..."
VOLUME=$(docker volume ls -q | grep miniodata | head -1)
docker run --rm -v "$VOLUME":/data:ro -v "$BACKUP_DIR":/backup debian:bookworm-slim \
    tar czf "/backup/minio-$STAMP.tar.gz" -C /data .

find "$BACKUP_DIR" -name 'db-*.dump' -mtime +7 -delete
find "$BACKUP_DIR" -name 'minio-*.tar.gz' -mtime +7 -delete

echo "[$(date '+%F %T')] done: $(du -sh "$BACKUP_DIR" | cut -f1) in $BACKUP_DIR"
