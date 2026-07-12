#!/usr/bin/env bash
# gopan 每日备份:pg_dump + MinIO 数据卷打包。
# cron 示例:0 4 * * * cd /opt/gopan && ./scripts/backup.sh >> /var/log/gopan-backup.log 2>&1
#
# 备份是全量快照,不是增量:每份 minio tar ≈ 当前用量。KEEP_DAYS 份同时躺在盘上,
# 小盘务必调低(如 KEEP_DAYS=2)并配异地备份,否则"备份"本身就是撑爆磁盘的元凶——
# 盘满会连带打挂同机所有服务,比丢备份严重得多。所以下面还有一道水位预检。
set -euo pipefail

BACKUP_DIR=${BACKUP_DIR:-/var/backups/gopan}
KEEP_DAYS=${KEEP_DAYS:-7}
COMPOSE="docker compose -f docker-compose.prod.yml"
STAMP=$(date +%F)

mkdir -p "$BACKUP_DIR"

VOLUME=$(docker volume ls -q | grep miniodata | head -1)
VOLPATH=$(docker volume inspect "$VOLUME" --format '{{.Mountpoint}}')

# 水位预检:要写的量(minio 卷未压缩大小,压缩后只会更小)对比可用空间,留 20% 余量。
NEED=$(du -sb "$VOLPATH" | cut -f1)
AVAIL=$(df -B1 --output=avail "$BACKUP_DIR" | tail -1)
if [ "$AVAIL" -lt $(( NEED * 12 / 10 )) ]; then
    echo "[$(date '+%F %T')] 中止:可用 $(( AVAIL / 1048576 ))MB < 需要 $(( NEED * 12 / 10 / 1048576 ))MB" >&2
    echo "  调低 KEEP_DAYS、清理旧备份,或把 BACKUP_DIR 挪到别的盘。宁可这次不备份,也不能写满盘。" >&2
    exit 1
fi

echo "[$(date '+%F %T')] pg_dump ..."
$COMPOSE exec -T postgres pg_dump -U gopan -Fc gopan > "$BACKUP_DIR/db-$STAMP.dump"

echo "[$(date '+%F %T')] minio volume tar ..."
docker run --rm -v "$VOLUME":/data:ro -v "$BACKUP_DIR":/backup debian:bookworm-slim \
    tar czf "/backup/minio-$STAMP.tar.gz" -C /data .

find "$BACKUP_DIR" -name 'db-*.dump' -mtime +"$KEEP_DAYS" -delete
find "$BACKUP_DIR" -name 'minio-*.tar.gz' -mtime +"$KEEP_DAYS" -delete

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
