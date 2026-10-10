#!/bin/bash
set -euo pipefail
umask 077
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

cd /home/ubuntu/deepstudy-deploy

# 이 스크립가 동시에 두 번 실행되지 않도록 잠금
exec 9>backup-db.lock
if ! flock -n 9; then
  echo "이미 백업이 실행 중입니다."
  exit 0
fi

db_password=$(sed -n 's/^DB_PASSWORD=//p' app.env)
: "${db_password:?DB_PASSWORD가 비어 있습니다}"

backup_stamp=$(date +%Y%m%d-%H%M%S)
backup_name="scott-${backup_stamp}"
backup_dir=/opt/oracle/oradata/scott-backups

printf '%s\n' "$db_password" |
  docker exec -i -u oracle oracle-db expdp \
  scott@FREEPDB1 \
  JOB_NAME="SCOTT_BACKUP_${backup_stamp}" \
  SCHEMAS=SCOTT \
  DIRECTORY=SCOTT_BACKUP_DIR \
  DUMPFILE="${backup_name}.dmp" \
  LOGFILE="${backup_name}.log" \
  FLASHBACK_TIME=SYSTIMESTAMP

unset db_password

# 로컬 PC로 다운로드하기 편하도록 EC2 호스트에도 복사
mkdir -p db-backups
docker cp "oracle-db:${backup_dir}/${backup_name}.dmp" db-backups/
docker cp "oracle-db:${backup_dir}/${backup_name}.log" db-backups/
chmod 600 "db-backups/${backup_name}.dmp" "db-backups/${backup_name}.log"

# 새 백업의 덤프와 로그를 S3에도 보관
s3_dest="s3://deepstudy-db-backups-406579089357-ap-northeast-2-an/db-backups"

/usr/local/bin/aws s3 cp \
  "db-backups/${backup_name}.dmp" \
  "${s3_dest}/${backup_name}.dmp" \
  --region ap-northeast-2

/usr/local/bin/aws s3 cp \
  "db-backups/${backup_name}.log" \
  "${s3_dest}/${backup_name}.log" \
  --region ap-northeast-2

echo "S3 백업 업로드 완료: ${backup_name}"

# 성공한 백업만 최근 7세트 보관
# 0: 삭제 예정 목록만 출력, 1: 실제 삭제
BACKUP_PRUNE=${BACKUP_PRUNE:-1}
keep_count=7

# 날짜가 포함된 파일 이름을 일정한 순서로 정렬
export LC_ALL=C
shopt -s nullglob
successful_backups=()

for log_file in db-backups/scott-*.log; do
  name=${log_file##*/}
  name=${name%.log}

  # 정규 백업 이름만 처리: 테스트 파일 등은 제외
  [[ "$name" =~ ^scott-[0-9]{8}-[0-9]{6}$ ]] || continue

  # 덤프와 로그가 모두 있고, 로그에 정상 완료 기록이 있어야 함
  [[ -s "db-backups/${name}.dmp" && -s "$log_file" ]] || continue

  if grep -Eq '^Job .* successfully completed' "$log_file"; then
    successful_backups+=("$name")
  fi
done

backup_count=${#successful_backups[@]}
echo "호스트에서 확인된 성공 백업: ${backup_count}세트"

if (( backup_count > keep_count )); then
  remove_count=$((backup_count - keep_count))

  # 이름순으로 오래된 백업부터 정리
  for ((i=0; i<remove_count; i++)); do
    old_name=${successful_backups[i]}

    if [[ "$BACKUP_PRUNE" == "1" ]]; then
      # 컨테이너의 해당 덤프·로그만 삭제
      docker exec -u oracle oracle-db rm -f -- \
        "${backup_dir}/${old_name}.dmp" \
        "${backup_dir}/${old_name}.log"

      # 컨테이너 정리가 성공한 뒤 호스트의 같은 세트 삭제
      rm -f -- \
        "db-backups/${old_name}.dmp" \
        "db-backups/${old_name}.log"

      echo "오래된 성공 백업 삭제: ${old_name}"
    else
      echo "[삭제 안 함] 정리 예정: ${old_name}.dmp / ${old_name}.log"
    fi
  done
else
  echo "성공 백업이 ${keep_count}세트 이하이므로 정리 대상이 없습니다."
fi

echo "백업 및 호스트 복사 완료: ${backup_name}"
