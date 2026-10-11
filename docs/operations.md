# 운영 가이드

## 1. 운영 환경

| 항목 | 설정 |
|---|---|
| 서버 | EC2 Ubuntu |
| 접속 사용자 | ubuntu |
| 서비스 주소 | https://moducafe.duckdns.org/ |
| 작업 폴더 | /home/ubuntu/deepstudy-deploy |
| 첨부파일 폴더 | /home/ubuntu/deepstudy-uploads |
| 서버 시간대 | Asia/Seoul |

Docker Compose로 다음 컨테이너를 관리합니다.

| Compose 서비스 | 컨테이너 | 역할 |
|---|---|---|
| nginx | nginx | HTTPS 및 리버스 프록시 |
| app | deepstudy | Tomcat / Spring MVC |
| oracle | oracle-db | Oracle DB |

앱은 host 네트워크를 사용합니다.
Oracle은 서버 내부의 `127.0.0.1:1521`로 접속하며,
서비스명은 `FREEPDB1`입니다.

## 2. 주요 파일과 저장 위치

작업 폴더를 기준으로 합니다.

| 경로 | 용도 |
|---|---|
| compose.yaml | 컨테이너 구성 |
| app.env | 앱의 DB 접속 설정 |
| oracle.env | Oracle 컨테이너 설정 |
| deploy/nginx/default.conf | Nginx 설정 |
| deploy/tomcat/ROOT.xml | Tomcat 설정 |
| deploy/scripts/ | 백업·인증서 운영 스크립트 |
| logs/ | 예약 작업 로그 |
| db-backups/ | EC2 호스트의 DB 덤프·로그 |
| certbot/conf/ | 인증서 및 갱신 설정 |
| certbot/www/ | 인증서 발급·갱신용 웹 인증 경로 |

Oracle 데이터는 Docker 외부 볼륨 `oracle-data`에 저장합니다.
첨부파일은 `/home/ubuntu/deepstudy-uploads`를
앱 컨테이너의 `/data/uploads`에 연결하여 저장합니다.

환경 설정 파일, 비밀번호, 개인키, 인증서는 Git에 포함하지 않습니다.

## 3. 배포

`main` 브랜치에 push하면 GitHub Actions가 다음 작업을 수행합니다.

1. WAR 빌드 및 Docker 이미지 업로드
2. 배포 설정과 운영 스크립트를 EC2로 복사
3. Nginx 설정 검사 및 앱 갱신
4. `/cafe`의 HTTP 200 응답 확인

배포 중 Oracle 컨테이너는 유지합니다.
현재 워크플로는 테스트 실행을 생략합니다.

배포 후 EC2에서 상태를 확인합니다.

```bash
cd /home/ubuntu/deepstudy-deploy
docker compose ps
```

`docker compose ps`는 컨테이너의 실행 상태를 보여줍니다.

앱의 최근 로그를 확인합니다.

```bash
docker compose logs --tail=100 app
```

`--tail=100`은 마지막 100줄만 출력합니다.

## 4. 예약 작업

모든 시간은 서버의 `Asia/Seoul` 기준입니다.

| 작업 | 시간 | 사용자 |
|---|---|---|
| 인증서 갱신 확인 | 매일 10:00, 16:00 | ubuntu |
| DB 백업 | 매일 16:00 | ubuntu |
| 첨부파일 백업 | 매일 16:10 | root |

EC2가 꺼져 있으면 예약 작업은 실행되지 않습니다.

등록 내용은 다음 명령으로 조회합니다.

```bash
crontab -l
sudo crontab -l
```

첫 번째는 ubuntu, 두 번째는 root의 예약 작업입니다.

### ubuntu의 cron

```cron
0 10,16 * * * /bin/bash /home/ubuntu/deepstudy-deploy/deploy/scripts/renew-cert.sh >> /home/ubuntu/deepstudy-deploy/logs/renew-cert.log 2>&1
0 16 * * * /bin/bash /home/ubuntu/deepstudy-deploy/deploy/scripts/backup-db.sh >> /home/ubuntu/deepstudy-deploy/logs/backup-db.log 2>&1
```

### root의 cron

```cron
10 16 * * * /bin/bash /home/ubuntu/deepstudy-deploy/deploy/scripts/backup-attachments.sh >> /home/ubuntu/deepstudy-deploy/logs/backup-attachments.log 2>&1
```

`>>`는 로그를 이어 쓰고, `2>&1`은 오류도 같은 로그에 기록합니다.
로그 폴더는 예약 실행 전에 존재해야 합니다.

## 5. 로그 확인

```bash
tail -n 80 /home/ubuntu/deepstudy-deploy/logs/backup-db.log
tail -n 80 /home/ubuntu/deepstudy-deploy/logs/backup-attachments.log
tail -n 80 /home/ubuntu/deepstudy-deploy/logs/renew-cert.log
```

`tail -n 80`은 파일의 마지막 80줄을 조회합니다.

| 작업 | 확인할 내용 |
|---|---|
| DB 백업 | Data Pump 정상 완료, S3 업로드 완료, 스크립트 완료 |
| 첨부파일 백업 | 시작·완료 로그, 오류 유무 |
| 인증서 확인 | 갱신 결과, Nginx 설정 검사·reload 결과 |

인증서 갱신 시기가 아니면
`Certificate not yet due for renewal`로 끝나는 것도 정상입니다.

cron이 실제로 실행했는지는 다음 명령으로 확인합니다.

```bash
sudo journalctl -u cron --since today --no-pager
```

## 6. 수동 실행

아래 명령은 상태 조회가 아니라 실제 작업을 실행합니다.

### DB 백업

```bash
bash /home/ubuntu/deepstudy-deploy/deploy/scripts/backup-db.sh
```

새 덤프·로그를 생성하고 S3에 업로드합니다.
정상 완료된 백업은 최근 7세트를 유지하며,
정리 대상의 호스트·컨테이너 사본을 삭제합니다.
S3 사본은 이 스크립트에서 삭제하지 않습니다.

### 첨부파일 백업

```bash
sudo bash /home/ubuntu/deepstudy-deploy/deploy/scripts/backup-attachments.sh
```

첨부파일 폴더를 읽기 위해 root 권한으로 실행합니다.
새 파일과 변경된 파일을 S3에 반영하며,
EC2에서 삭제된 파일도 이미 백업됐다면 S3에는 유지합니다.

### 인증서 확인

```bash
bash /home/ubuntu/deepstudy-deploy/deploy/scripts/renew-cert.sh
```

인증서 갱신 필요 여부를 확인하고,
Nginx 설정 검사 후 reload합니다.

## 7. 외부 백업

| 대상 | S3 저장 위치 |
|---|---|
| DB | deepstudy-db-backups-406579089357-ap-northeast-2-an / db-backups/ |
| 첨부파일 | deepstudy-attachment-backups-406579089357-ap-northeast-2-an / attachments/ |

EC2는 IAM 역할로 S3에 접근합니다.
현재 두 버킷에는 자동 삭제 규칙을 설정하지 않았습니다.

DB 백업은 실행마다 별도의 덤프·로그를 생성합니다.
첨부파일 백업은 같은 경로에 변경 내용을 반영하며,
날짜별 전체 스냅샷이나 파일의 이전 버전을 보관하는 방식은 아닙니다.

## 8. 확인한 예약 실행

2026-10-11 기준:

- DB: 16:00 예약 실행 후 S3 저장 확인
- 첨부파일: 16:10 시작·완료 로그 확인
- 인증서: 16:00 cron 실행 및 정상 처리 로그 확인

전체 복구 준비와 절차는 [복구 가이드](recovery.md)를 참고합니다.