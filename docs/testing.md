# 핵심 테스트 가이드

## 실행

프로젝트 루트에서 JDK 8과 Maven으로 실행합니다.

```bash
mvn clean test
```

`clean`은 이전 빌드 결과를 제거하고, `test`는 소스 컴파일과 테스트를 실행합니다.
STS에서는 프로젝트 우클릭 → Run As → Maven test로 실행할 수 있습니다.
Java 8에서 46개 케이스가 통과했으며, 실패·오류·건너뛴 케이스는 없습니다.
파라미터 테스트는 하나의 메서드를 여러 입력으로 실행하므로 메서드 수보다 실행 건수가 많습니다.
상세 결과는 `target/surefire-reports/`에 생성됩니다.

## 선정 기준

권한 위반, 파일 유실, 잘못된 가입과 유해 HTML을 먼저 검증합니다.
실제 검증 없이 이름만 있던 테스트 후보는 삭제했습니다.
테스트는 운영 DB, EC2, S3에 연결하지 않습니다.
파일 작업은 JUnit이 생성하고 정리하는 임시 폴더에서 수행합니다.

## 남긴 테스트

| 파일 | 중요한 검증 |
|---|---|
| `CafeBoardAccessServiceTests` | 공개/회원 전용 접근과 MEMBER·MANAGER·OWNER 권한 조합 |
| `BoardControllerTests` | 작성자·쓰기 권한 검사, 다른 게시판의 글 번호 차단, 요청의 작성자·게시판 번호 조작 무시 |
| `BoardAttachControllerTests` | 읽기 권한이 없으면 파일 조회 차단, 허용된 본문 이미지만 inline 표시 |
| `BoardAttachServiceTests` | 다른 게시글의 첨부파일 번호로 삭제 불가 |
| `FileStorageServiceTests` | 트랜잭션 필수, UUID 저장, 실패 파일 정리, 커밋 후 삭제·롤백 시 유지, 폴더 밖 경로 차단 |
| `CafeMemberServiceTests` | 최초 가입은 승인 대기, 활동·대기·강퇴 상태에서 중복 가입 차단 |
| `MemberServiceTests` | 회원·기본 권한 저장, 중복 아이디·비밀번호 불일치 차단, BCrypt 해시 |
| `MemberControllerTests` | 정상 가입 이동, 입력 검증, 중복 아이디·비밀번호 불일치 오류 표시 |
| `BoardHtmlSanitizerTests` | 허용된 YouTube iframe·내부 이미지 유지, 외부 iframe·이미지 및 실행 가능한 HTML 제거 |

## 나중에 읽는 순서

1. `CafeBoardAccessServiceTests`: 준비 → 실행 → 결과 확인 흐름과 권한 표
2. `MemberServiceTests`: Mockito로 DB 호출을 대체하고 저장 값을 확인하는 방법
3. `FileStorageServiceTests`: 실제 파일 작업과 Spring 트랜잭션 콜백 검증

`assertThrows`는 거부해야 할 요청이 실제로 예외를 발생시키는지 확인합니다.
`verify(..., never())`는 거부한 요청이 저장·삭제까지 진행하지 않는지 확인합니다.
`@ParameterizedTest`는 같은 규칙을 역할·상태 등 여러 입력으로 검증합니다.

## 검증 범위와 한계

- Mapper는 모의 객체이므로 실제 Oracle SQL·제약조건·DB 행 롤백은 검증하지 않습니다.
- 활동 회원 조회의 `STATUS = 'ACTIVE'` SQL 필터는 DB 통합 테스트 범위입니다.
- 파일 테스트는 Spring의 트랜잭션 처리와 콜백을 실제로 실행하지만,
  DB 없는 테스트용 트랜잭션 매니저를 사용합니다.
- 게시글·첨부파일 컨트롤러 테스트는 메서드를 직접 호출하며,
  Spring Security 필터·인증·CSRF·HTTP 라우팅 전체는 검증하지 않습니다.
- 회원가입 HTTP 요청은 MockMvc로 검증하며,
  실제 브라우저·Tomcat을 띄우지 않습니다.
- GitHub Actions는 WAR 빌드 전에 테스트를 실행하며,
  테스트가 실패하면 이미지 업로드와 배포를 진행하지 않습니다.

앞으로 규칙을 변경할 때 해당 테스트를 함께 수정합니다.
Oracle 통합 테스트와 전체 로그인 흐름은 필요해질 때 별도로 추가합니다.
