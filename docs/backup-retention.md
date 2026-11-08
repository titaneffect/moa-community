# 백업 보관 제한 적용 절차

2026-11-08에 EC2 스크립트 배포, 최초 관리 전체 백업·검증·S3 업로드,
IAM 추가 권한 확인, 기존 아카이브 로그 25개와 오래된 S3 덤프 정리, cron 등록을 진행했습니다.
아래는 적용 절차와 이후 운영 참고 자료입니다. 로컬 파일 수정만으로 EC2 파일이나 cron이 바뀌지는 않습니다.

## 보관 기준

| 대상 | 기준 |
|---|---|
| Data Pump | 성공 완료 로그와 비어 있지 않은 덤프가 모두 있는 최근 7세트. EC2·컨테이너·S3에 적용 |
| RMAN | 주 1회 `DATABASE FORCE PLUS ARCHIVELOG` 전체 백업. 검증·S3 보관된 최근 2묶음 유지 |
| 아카이브 원본 | 해당 실행의 전체 백업·검증·S3 확인이 끝난 뒤, 실행 시작 전 생성됐고 디스크에 백업된 로그만 RMAN으로 삭제 |

기본 `BACKUP_PRUNE=0`은 삭제 예정 목록만 출력합니다. 백업 생성과 S3 업로드는 실제로 진행합니다.
`BACKUP_PRUNE=1`일 때만 삭제합니다. 각 묶음은 증분 백업이 아닌 독립된 전체 백업입니다.
RMAN은 실행별로 포함된 아카이브 로그를 함께 보관하므로, 2묶음만 남겨도 각 전체 백업 완료 시점으로 복구하는 데 필요한 파일을 함께 유지합니다.
주 1회 실행만으로 장애 직전까지 복구할 수 있는 것은 아닙니다. 마지막 RMAN 백업 이후는
일일 `.dmp` 백업 또는 별도 아카이브 백업이 필요합니다. 기존 일일 `.dmp` cron은 유지합니다.

S3 RMAN 경로는 `db-backups/rman-managed/FREE/실행시각/`입니다.
`_SUCCESS.json`은 검증이 끝난 파일의 이름·크기·SHA-256·DBID를 기록합니다.
업로드 후 HEAD로 크기와 SHA-256 메타데이터를 비교하고 완료 기록을 다시 읽습니다.
이는 S3에서 전량 다운로드하여 재해시하거나 별도 DB에 실제 복원하는 검증을 대신하지 않습니다.
로컬 RMAN `VALIDATE BACKUPSET`은 매 실행에 포함됩니다.

S3의 오래된 묶음을 지우기 전에 남길 2묶음을 확인합니다. 삭제 실패 시 S3 완료 기록을 마지막에 지우므로 재시도할 수 있습니다.
미완료 로컬 백업이 있으면 이전 묶음 정리를 중단합니다. 해당 백업의 로그·S3 업로드 상태를 확인한 뒤 수동으로 복구합니다.
기존 `rman-backups` 폴더의 수동 백업은 더 최신인 관리 전체 백업 2묶음이 S3에서 확인된 뒤에만
정리 대상이 됩니다. 파일 수정 시각이 남길 두 묶음의 시작 시각보다 오래된 RMAN 백업 파일만
정확한 경로로 RMAN에 삭제를 요청합니다. 다른 폴더, 최신 수동 백업, 알 수 없는 파일은 제외합니다.
날짜별 작업 로그는 진단용으로 남습니다. 대용량 백업 파일 보관 제한과 별개입니다.

## 사전 확인과 배포

필요 파일:

- `deploy/scripts/backup-db.sh`
- `deploy/scripts/backup-rman.sh`
- `deploy/scripts/backup-store.py`

GitHub Actions의 파일 복사 목록에도 추가했습니다. cron은 자동 변경하지 않습니다.
EC2에 위 파일을 배포한 뒤 다음을 확인합니다. 셸 파일은 LF 줄바꿈이어야 합니다.

```bash
cd /home/ubuntu/deepstudy-deploy
python3 --version
bash -n deploy/scripts/backup-db.sh
bash -n deploy/scripts/backup-rman.sh
python3 deploy/scripts/backup-store.py --help
/usr/local/bin/aws s3api get-bucket-versioning \
  --bucket deepstudy-db-backups-406579089357-ap-northeast-2-an \
  --region ap-northeast-2
```

Ubuntu Python 3.8 이상과 AWS CLI v2가 필요합니다. IAM 역할에는 기존 목록·읽기·쓰기 권한 외에
해당 백업 경로의 `s3:DeleteObject`와 버킷의 `s3:GetBucketVersioning`이 필요합니다.
권한 오류는 작업을 중단합니다. 버전 관리가 `Enabled` 또는 `Suspended`이면 자동 삭제를 차단합니다.
버전 관리 버킷에서는 일반 삭제가 실제 저장 용량을 줄이지 않을 수 있어 별도 정책 검토가 필요합니다.
기존 EC2 역할에서 `GetBucketVersioning` AccessDenied를 확인했습니다.
IAM 역할 `deepstudy-ec2-backup-role`에 [최소 추가 권한 정책](../deploy/iam/backup-retention-policy.json)을
기존 정책과 별도로 추가해야 합니다. 기존 쓰기·읽기 정책을 대체하지 않습니다.

## 삭제 없는 확인

기존 S3 덤프만 확인합니다. 새 백업은 생성하지 않습니다.

```bash
python3 deploy/scripts/backup-store.py prune-dumps
```

사용자가 공유한 8세트 기준으로 가장 오래된 `scott-20261011-015947` 한 세트가 삭제 예정입니다.
목록이 이후 변경됐다면 실제 출력도 달라질 수 있습니다. RMAN 하위 경로, 실패 로그, 짝 없는 파일은 제외합니다.

RMAN은 먼저 아래를 1회 수동 실행합니다. root가 Docker 볼륨의 파일을 직접 읽어 S3로 보내므로
호스트에 백업 사본을 추가로 만들지 않습니다. 여유 공간이 8GiB 미만이면 시작하지 않습니다.

```bash
sudo env BACKUP_PRUNE=0 /bin/bash \
  /home/ubuntu/deepstudy-deploy/deploy/scripts/backup-rman.sh
```

이 명령은 새 전체 백업과 S3 업로드를 실제로 만듭니다. 반복해서 실행하지 않습니다.
출력, `logs/rman-실행시각.log`, S3 `_SUCCESS.json`, 여유 공간을 확인합니다.
실제 복원 테스트는 별도 Oracle 테스트 환경에서 진행해야 합니다.

## 실제 정리와 예약 작업

삭제 예정 목록을 확인한 뒤 덤프의 S3 정리만 수동 적용할 수 있습니다.

```bash
python3 deploy/scripts/backup-store.py prune-dumps --apply
```

RMAN은 완료된 실행 이름을 지정하면 새 전체 백업 없이 정리 단계만 재개할 수 있습니다.
아래 실행 이름은 2026-11-08 첫 관리 백업입니다. S3 완료 기록과 파일을 다시 확인한 뒤,
백업 시작 전까지의 백업된 아카이브 원본과 오래된 묶음을 정리합니다.

```bash
sudo python3 deploy/scripts/backup-store.py finalize-rman --stamp 20261108-210809
# 미리보기와 IAM 권한을 확인한 뒤 적용
sudo python3 deploy/scripts/backup-store.py finalize-rman --stamp 20261108-210809 --apply
```

파일을 업로드만 하고 완료 기록이 없는 미완료 실행은 이 명령으로 정리할 수 없습니다.

ubuntu의 기존 인증서 cron은 그대로 두고 DB 백업 행만 다음으로 교체합니다. 중복 추가하지 않습니다.

```cron
0 16 * * * BACKUP_PRUNE=1 /bin/bash /home/ubuntu/deepstudy-deploy/deploy/scripts/backup-db.sh >> /home/ubuntu/deepstudy-deploy/logs/backup-db.log 2>&1
```

root의 기존 첨부파일 cron은 유지하고 아래를 추가합니다. 서버 시간대는 Asia/Seoul입니다.
매주 일요일 17시에 실행합니다. 다음 예약을 기다릴 여유 공간이 있는지도 확인합니다.

```cron
0 17 * * 0 BACKUP_PRUNE=1 /bin/bash /home/ubuntu/deepstudy-deploy/deploy/scripts/backup-rman.sh >> /home/ubuntu/deepstudy-deploy/logs/backup-rman.log 2>&1
```

정리 없는 백업만 성공했다면 아직 재발 방지 적용이 완료된 것이 아닙니다.
실제 정리 실행 결과, 남은 백업 수, `df -h /`, `/cafe` HTTP 200까지 확인합니다.
백업 오류나 용량 부족으로 정리가 중단될 수 있으므로 디스크 사용량도 계속 점검합니다.

## 로컬 검증

```bash
python3 -m unittest discover -s deploy/tests -v
bash -n deploy/scripts/backup-db.sh
bash -n deploy/scripts/backup-rman.sh
```

테스트는 S3·Docker 호출을 대체합니다. 실제 삭제나 원격 접속을 하지 않습니다.
실패/불완전 덤프, 미완료 업로드, S3 검증 오류, RMAN 삭제 오류 때 보관할 백업이 삭제되지 않는지 확인합니다.

## 운영 적용 기록

- EC2 Python 3.12.3에서 테스트 17개 통과, 셸 문법 검사 통과.
- 관리 전체 백업 `20261108-210809`, DBID `1509902213`: 백업 파일 6개 RMAN 검증 통과.
- S3에 백업 6개와 완료 기록 1개를 저장하고 크기·해시 메타데이터 확인.
- 최초 적용 시 S3 완료 백업은 1묶음입니다. 성공 백업이 2묶음 미만이면 이전 전체 백업은 삭제하지 않습니다.
- 검증된 백업에 포함된 아카이브 원본 25개 정리, 디스크 여유 5.7GiB → 9.5GiB.
- 일일 덤프 `scott-20261108-212413` 생성·S3 업로드와 로컬·컨테이너·S3 7세트 정리 실제 실행 성공.
- 최종 S3: 덤프 7세트, 관리 RMAN 전체 백업 1묶음. Oracle healthy, HTTPS `/cafe` HTTP 200.
- 사이트 `/cafe` HTTP 200 확인.
- ubuntu DB cron은 매일 16시 `BACKUP_PRUNE=1`, root RMAN cron은 일요일 17시 `BACKUP_PRUNE=1`로 등록.
- 기존 인증서·첨부파일 cron 유지. 변경 직전 cron은 EC2 `.backup-retention-cron-20261108-212408/`에 보관.
- 실제 별도 DB 복원과 향후 세 번째 관리 백업 생성에 따른 오래된 전체 묶음 정리는 아직 운영 검증하지 않았습니다.
