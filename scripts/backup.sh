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

# 可选异地:设 BACKUP_REMOTE(rclone 远端,如 r2:gopan-backup)后同步整个备份目录。
# 远端配置一次即可:rclone config(任意 S3/网盘)。本机备份挡误删,异地挡整机故障。
if [ -n "${BACKUP_REMOTE:-}" ]; then
  if command -v rclone > /dev/null; then
    echo "[$(date '+%F %T')] rclone sync -> $BACKUP_REMOTE ..."
    rclone sync "$BACKUP_DIR" "$BACKUP_REMOTE" --transfers 2
  else
    echo "[$(date '+%F %T')] 警告:设置了 BACKUP_REMOTE 但未安装 rclone,跳过异地备份" >&2
  fi
fi

echo "[$(date '+%F %T')] done: $(du -sh "$BACKUP_DIR" | cut -f1) in $BACKUP_DIR"
