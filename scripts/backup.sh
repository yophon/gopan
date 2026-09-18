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
# 异地备份(有第二台机器时的首选):BACKUP_OFFSITE_SSH 填 ssh 别名或 user@host,需先配好免密。
# 刻意不用 --delete:本地留 2 份、异地留 30 份是两套策略,异地的旧副本不该被本地策略带着删。
BACKUP_OFFSITE_SSH=${BACKUP_OFFSITE_SSH:-}
BACKUP_OFFSITE_DIR=${BACKUP_OFFSITE_DIR:-/srv/gopan-backup}
BACKUP_OFFSITE_KEEP_DAYS=${BACKUP_OFFSITE_KEEP_DAYS:-30}
COMPOSE="docker compose -f docker-compose.prod.yml"
STAMP=$(date +%F)
OFFSITE_FAILED=0

# --status:人查"上次成功是什么时候"。cron 只往日志追加、不记退出码,
# 服务器没配 MTA 时,这是判断异地是否还活着的唯一线索。
if [ "${1:-}" = "--status" ]; then
    printf '本地最新 dump: %s\n' "$(ls -1t "$BACKUP_DIR"/db-*.dump 2>/dev/null | head -1 || echo '无')"
    printf '异地上次成功: %s\n' "$(cat "$BACKUP_DIR/.offsite-ok" 2>/dev/null || echo '从未成功')"
    if [ -n "$BACKUP_OFFSITE_SSH" ]; then
        ssh -o BatchMode=yes -o ConnectTimeout=10 "$BACKUP_OFFSITE_SSH" \
            "ls -la $BACKUP_OFFSITE_DIR 2>/dev/null | tail -6; du -sh $BACKUP_OFFSITE_DIR" 2>/dev/null \
            || echo "异地不可达:$BACKUP_OFFSITE_SSH"
    else
        echo '未配置 BACKUP_OFFSITE_SSH'
    fi
    exit 0
fi

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

# 可选异地:rsync over ssh 推给第二台机器(增量、可续传、无需装 rclone)。
# 本地备份此刻已经完成,所以这里失败不影响本地那份 —— 但绝不能静默:
# 写醒目警告 + 失败时间戳,并以独立退出码 9 收尾(配了 MTA 的 cron 才会报警)。
if [ -n "$BACKUP_OFFSITE_SSH" ]; then
  if command -v rsync > /dev/null; then
    echo "[$(date '+%F %T')] rsync -> $BACKUP_OFFSITE_SSH:$BACKUP_OFFSITE_DIR ..."
    if rsync -a --partial --exclude '.offsite-*' --exclude 'pre-*' \
         "$BACKUP_DIR"/ "$BACKUP_OFFSITE_SSH:$BACKUP_OFFSITE_DIR/"; then
      # 异地自己的保留期:只清备份产物,不碰目录里别的东西
      ssh -o BatchMode=yes -o ConnectTimeout=10 "$BACKUP_OFFSITE_SSH" \
          "find $BACKUP_OFFSITE_DIR -maxdepth 1 -type f \( -name 'db-*.dump' -o -name 'minio-*.tar.gz' \) -mtime +$BACKUP_OFFSITE_KEEP_DAYS -delete" \
          || echo "[$(date '+%F %T')] 警告:异地旧备份清理失败(备份本身不受影响)" >&2
      date -Is > "$BACKUP_DIR/.offsite-ok"
      echo "[$(date '+%F %T')] 异地完成:$(ssh -o BatchMode=yes "$BACKUP_OFFSITE_SSH" "du -sh $BACKUP_OFFSITE_DIR | cut -f1") at $BACKUP_OFFSITE_SSH"
    else
      echo "[$(date '+%F %T')] 警告:异地推送失败!本地备份已完成,异地副本未更新。" >&2
      echo "  排查:$BACKUP_OFFSITE_SSH 是否可达、免密是否失效;试 ./scripts/backup.sh --status" >&2
      date -Is > "$BACKUP_DIR/.offsite-fail"
      OFFSITE_FAILED=1
    fi
  else
    echo "[$(date '+%F %T')] 警告:设置了 BACKUP_OFFSITE_SSH 但本机无 rsync,跳过异地备份" >&2
    OFFSITE_FAILED=1
  fi
fi

# 可选异地(备选):设 BACKUP_REMOTE(rclone 远端,如 r2:gopan-backup)后同步整个备份目录。
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

# 9 = 本地备份成功、仅异地失败。用独立退出码是为了让"人看日志"和"机器看退出码"都能分辨。
if [ "$OFFSITE_FAILED" = "1" ]; then
  exit 9
fi
