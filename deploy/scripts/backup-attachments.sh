#!/bin/bash
set -euo pipefail
umask 077
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

# 관리자 권한으로 실행해야 첨부파일 폴더를 읽을 수 있음
if [[ "$EUID" -ne 0 ]]; then
  echo "sudo 또는 root의 cron으로 실행해주세요."
  exit 1
fi

# 동시에 두 번 실행되지 않도록 잠금
exec 9>/run/deepstudy-attachment-backup.lock
if ! flock -n 9; then
  echo "이미 첨부파일 백업이 실행 중입니다."
  exit 0
fi

upload_dir=/home/ubuntu/deepstudy-uploads
s3_dest=s3://deepstudy-attachment-backups-406579089357-ap-northeast-2-an/attachments/

# 원본 폴더가 없으면 실패로 처리
if [[ ! -d "$upload_dir" ]]; then
  echo "첨부파일 폴더가 없습니다: $upload_dir"
  exit 1
fi

echo "첨부파일 백업 시작: $(date -Is)"

/usr/local/bin/aws s3 sync \
  "$upload_dir/" \
  "$s3_dest" \
  --region ap-northeast-2 \
  --no-follow-symlinks \
  --no-progress

echo "첨부파일 백업 완료: $(date -Is)"
