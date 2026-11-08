#!/usr/bin/env python3
"""S3 retention for this deployment. All pruning is a preview unless --apply."""
import argparse
import datetime
import hashlib
import json
import os
import re
import subprocess
import sys
from pathlib import Path

BUCKET = 'deepstudy-db-backups-406579089357-ap-northeast-2-an'
DUMPS = 'db-backups/'
RMAN = 'db-backups/rman-managed/FREE/'
STAMP = re.compile(r'^[0-9]{8}-[0-9]{6}$')
PIECE = re.compile(r'^[A-Za-z0-9_-]+\.bkp$')
AWS = '/usr/local/bin/aws'


def run(args, **kwargs):
    return subprocess.check_output(args, **kwargs).decode('utf-8').strip()


def aws(*args):
    return run([AWS, *args, '--region', 'ap-northeast-2', '--no-cli-pager'])


def api(operation, **kwargs):
    args = ['s3api', operation, '--bucket', BUCKET]
    for name, value in kwargs.items():
        args += ['--' + name.replace('_', '-'), str(value)]
    return json.loads(aws(*args, '--output', 'json') or '{}')


def objects(prefix):
    # AWS CLI follows all pages; a failed listing aborts before any deletion.
    return {item['Key']: item['Size'] for item in
            api('list-objects-v2', prefix=prefix).get('Contents', [])}


def read_object(key):
    return aws('s3', 'cp', 's3://' + BUCKET + '/' + key, '-', '--no-progress')


def remove_object(key):
    api('delete-object', key=key)


def require_unversioned_bucket():
    if api('get-bucket-versioning').get('Status') in ('Enabled', 'Suspended'):
        raise RuntimeError('버전 관리 버킷입니다. 과거 버전 보관 정책을 먼저 확인해야 합니다.')


def prune_dumps(apply=False):
    if apply:
        require_unversioned_bucket()
    listing = objects(DUMPS)
    successful = []
    for key in sorted(listing):
        match = re.fullmatch(r'db-backups/(scott-[0-9]{8}-[0-9]{6})\.log', key)
        if not match:
            continue
        name = match[1]
        if listing[key] <= 0 or listing.get(DUMPS + name + '.dmp', 0) <= 0:
            continue
        log = read_object(key)
        if re.search(r'^Job .* successfully completed', log, re.MULTILINE):
            successful.append(name)
    old = successful[:-7]
    print('S3 성공 덤프: %d세트 / 유지: 7세트' % len(successful))
    for name in old:
        print(('[삭제] ' if apply else '[삭제 안 함] ') + name + '.dmp / .log')
        if apply:
            # Exact keys only: never recurse into the RMAN prefix.
            remove_object(DUMPS + name + '.dmp')
            remove_object(DUMPS + name + '.log')
    if not old:
        print('S3 덤프 정리 대상 없음')


def managed_root():
    mounts = json.loads(run(['docker', 'inspect', 'oracle-db']))[0]['Mounts']
    for mount in mounts:
        if (mount['Type'] == 'volume' and mount['Name'] == 'oracle-data'
                and mount['Destination'] == '/opt/oracle/oradata'):
            return Path(mount['Source']) / 'rman-managed'
    raise RuntimeError('oracle-data 볼륨 연결을 확인할 수 없습니다.')


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def check_manifest(manifest, stamp):
    if (manifest.get('stamp') != stamp or not STAMP.fullmatch(stamp)
            or not re.fullmatch(r'[0-9]+', str(manifest.get('dbid', '')))
            or manifest.get('kind') != 'full-plus-archivelog'
            or not manifest.get('files')):
        raise RuntimeError('유효하지 않은 RMAN 완료 기록: ' + stamp)
    names = set()
    for item in manifest['files']:
        if (not PIECE.fullmatch(item['name']) or item['name'] in names
                or item['size'] <= 0
                or not re.fullmatch(r'[0-9a-f]{64}', item['sha256'])):
            raise RuntimeError('유효하지 않은 백업 파일 기록: ' + stamp)
        names.add(item['name'])


def verify_remote(manifest):
    stamp = manifest['stamp']
    check_manifest(manifest, stamp)
    for item in manifest['files']:
        info = api('head-object', key=RMAN + stamp + '/' + item['name'])
        if (info['ContentLength'] != item['size']
                or info.get('Metadata', {}).get('sha256') != item['sha256']):
            raise RuntimeError('S3 크기/해시 메타데이터 불일치: ' + item['name'])


def rman_cutoff(stamp):
    sql = """WHENEVER OSERROR EXIT FAILURE
WHENEVER SQLERROR EXIT FAILURE
SET HEADING OFF FEEDBACK OFF PAGESIZE 0 VERIFY OFF ECHO OFF
SELECT TO_CHAR(MIN(s.start_time) - 1/86400, 'YYYY-MM-DD HH24:MI:SS')
FROM v$backup_set s JOIN v$backup_piece p
ON s.set_stamp=p.set_stamp AND s.set_count=p.set_count
WHERE p.handle LIKE '/opt/oracle/oradata/rman-managed/%s/%%' AND p.status='A';
EXIT;
""" % stamp
    cutoff = run(['docker', 'exec', '-i', '-u', 'oracle', 'oracle-db', 'sqlplus', '-s', '/', 'as', 'sysdba'],
                 input=sql.encode()).strip()
    datetime.datetime.strptime(cutoff, '%Y-%m-%d %H:%M:%S')
    return cutoff


def publish_rman(stamp, dbid):
    if not STAMP.fullmatch(stamp) or not dbid.isdigit():
        raise RuntimeError('잘못된 실행 이름 또는 DBID')
    directory = managed_root() / stamp
    paths = sorted(directory.glob('*.bkp'))
    if len(paths) < 3 or any(path.is_symlink() for path in paths):
        raise RuntimeError('검증된 전체 백업 파일이 부족합니다.')
    cutoff = rman_cutoff(stamp)
    manifest = {'stamp': stamp, 'dbid': dbid, 'kind': 'full-plus-archivelog',
                'archive_cutoff': cutoff,
                'files': [{'name': path.name, 'size': path.stat().st_size,
                           'sha256': sha256(path)} for path in paths]}
    check_manifest(manifest, stamp)
    for item in manifest['files']:
        aws('s3', 'cp', str(directory / item['name']),
            's3://' + BUCKET + '/' + RMAN + stamp + '/' + item['name'],
            '--metadata', 'sha256=' + item['sha256'], '--no-progress')
        print('업로드 완료: ' + item['name'], flush=True)
    verify_remote(manifest)
    record = directory / '_SUCCESS.json'
    record.write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    aws('s3', 'cp', str(record), 's3://' + BUCKET + '/' + RMAN + stamp
        + '/_SUCCESS.json', '--no-progress')
    remote = json.loads(read_object(RMAN + stamp + '/_SUCCESS.json'))
    if remote != manifest:
        raise RuntimeError('S3 완료 기록 불일치')
    print('S3 검증 및 완료 기록 저장: ' + stamp, flush=True)


def finalize_rman(stamp, apply=False):
    if not STAMP.fullmatch(stamp):
        raise RuntimeError('잘못된 실행 이름')
    manifest = json.loads(read_object(RMAN + stamp + '/_SUCCESS.json'))
    check_manifest(manifest, stamp)
    verify_remote(manifest)
    cutoff = manifest.get('archive_cutoff') or rman_cutoff(stamp)
    datetime.datetime.strptime(cutoff, '%Y-%m-%d %H:%M:%S')
    print(('정리 실행: ' if apply else '[삭제 안 함] ') + cutoff + ' 이전의 백업된 아카이브 로그', flush=True)
    if apply:
        require_unversioned_bucket()
        commands = ('DELETE NOPROMPT ARCHIVELOG UNTIL TIME '
                    '\"TO_DATE(\'%s\', \'YYYY-MM-DD HH24:MI:SS\')\" '
                    'BACKED UP 1 TIMES TO DEVICE TYPE DISK;\nEXIT;\n') % cutoff
        output = run(['docker', 'exec', '-i', '-u', 'oracle', 'oracle-db', 'rman', 'target', '/'],
                     input=commands.encode())
        print(output)
        if re.search(r'^(?:RMAN-|ORA-)[0-9]+:', output, re.MULTILINE):
            raise RuntimeError('아카이브 정리 오류. 백업 묶음 정리를 중단합니다.')
    prune_rman(apply)


def prune_legacy(root, oldest, apply):
    # Retire the original manual baseline only after TWO newer full bundles
    # have passed remote verification. Never touch newer manual backups.
    directory = root.parent / 'rman-backups'
    if not directory.is_dir():
        return
    zone = datetime.timezone(datetime.timedelta(hours=9))
    before = datetime.datetime.strptime(oldest, '%Y%m%d-%H%M%S').replace(tzinfo=zone).timestamp()
    pieces = [path for path in directory.iterdir()
              if path.is_file() and not path.is_symlink() and path.stat().st_mtime < before
              and (PIECE.fullmatch(path.name) or re.fullmatch(r'c-[0-9]+-[0-9]{8}-[0-9a-fA-F]{2}', path.name))]
    for path in sorted(pieces):
        print(('[삭제] ' if apply else '[삭제 안 함] ') + '대체된 수동 백업 ' + path.name, flush=True)
        if apply:
            commands = "DELETE NOPROMPT BACKUPPIECE '/opt/oracle/oradata/rman-backups/%s';\nEXIT;\n" % path.name
            output = run(['docker', 'exec', '-i', '-u', 'oracle', 'oracle-db', 'rman', 'target', '/'],
                         input=commands.encode())
            print(output)
            if re.search(r'^(?:RMAN-|ORA-)[0-9]+:', output, re.MULTILINE) or path.exists():
                raise RuntimeError('수동 백업 정리 실패. RMAN 등록 상태를 확인하세요.')


def prune_rman(apply=False):
    if apply:
        require_unversioned_bucket()
    listing = objects(RMAN)
    manifests = []
    for key in sorted(listing):
        match = re.fullmatch(re.escape(RMAN) + r'([0-9]{8}-[0-9]{6})/_SUCCESS.json', key)
        if match:
            item = json.loads(read_object(key))
            check_manifest(item, match[1])
            manifests.append(item)
    if len(manifests) < 2:
        print('S3 전체 백업이 2묶음 미만이므로 이전 백업 정리 생략')
        return
    # Never mix different database incarnations/instances in the same policy.
    if len({item['dbid'] for item in manifests}) != 1:
        raise RuntimeError('서로 다른 DBID가 있어 자동 정리를 중단합니다.')
    for item in manifests[-2:]:
        verify_remote(item)
    root = managed_root()
    complete = {item['stamp'] for item in manifests}
    incomplete = [path.name for path in root.iterdir()
                  if path.is_dir() and STAMP.fullmatch(path.name)
                  and any(path.glob('*.bkp')) and path.name not in complete]
    if incomplete:
        raise RuntimeError('미완료 백업이 있어 정리 중단: ' + ', '.join(incomplete))
    prune_legacy(root, manifests[-2]['stamp'], apply)
    print('S3 검증된 전체 백업: %d묶음 / 유지: 2묶음' % len(manifests))
    for item in manifests[:-2]:
        stamp = item['stamp']
        print(('[삭제] ' if apply else '[삭제 안 함] ') + 'RMAN 묶음 ' + stamp, flush=True)
        if not apply:
            continue
        directory = root / stamp
        # Remove only exact RMAN pieces from an older, self-contained full bundle.
        commands = '\n'.join("DELETE NOPROMPT BACKUPPIECE '/opt/oracle/oradata/rman-managed/%s/%s';"
                             % (stamp, piece['name']) for piece in item['files'])
        output = run(['docker', 'exec', '-i', '-u', 'oracle', 'oracle-db', 'rman', 'target', '/'],
                     input=(commands + '\nEXIT;\n').encode())
        print(output)
        if re.search(r'^(?:RMAN-|ORA-)[0-9]+:', output, re.MULTILINE):
            raise RuntimeError('RMAN 삭제 오류. S3 사본은 유지합니다.')
        for piece in item['files']:
            if (directory / piece['name']).exists():
                raise RuntimeError('로컬 백업이 남아 있습니다. S3 사본은 유지합니다.')
        for piece in item['files']:
            remove_object(RMAN + stamp + '/' + piece['name'])
        # Marker last: interrupted remote deletion is retryable.
        remove_object(RMAN + stamp + '/_SUCCESS.json')
        if directory.exists():
            (directory / '_SUCCESS.json').unlink(missing_ok=True)
            # Preserve logs and unknown files for diagnosis; do not recursively delete.
    print('RMAN 묶음 정리 검사 완료')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['prune-dumps', 'publish-rman', 'prune-rman', 'check-bucket', 'finalize-rman'])
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--stamp')
    parser.add_argument('--dbid')
    args = parser.parse_args()
    if args.action == 'prune-dumps':
        prune_dumps(args.apply)
    elif args.action == 'publish-rman':
        publish_rman(args.stamp or '', args.dbid or '')
    elif args.action == 'check-bucket':
        require_unversioned_bucket()
        print('버전 관리 없는 S3 버킷 확인 완료')
    elif args.action == 'finalize-rman':
        finalize_rman(args.stamp or '', args.apply)
    else:
        prune_rman(args.apply)


if __name__ == '__main__':
    os.umask(0o077)
    try:
        main()
    except (RuntimeError, OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        print('백업 관리 중단: ' + str(error), file=sys.stderr)
        sys.exit(1)
