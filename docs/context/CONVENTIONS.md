# CONVENTIONS — diary-api 작업 규약

코드·설정·README·`docs/product/07-ideas.md`에서 관찰한 규약. 새 규칙을 만들지 않는다.

## 패키지·이름

- 기능 패키지 안에 `XxxController` · `XxxService` · `XxxMapper`(인터페이스) · `domain/` · `dto/`. 상담 도구가 쓰는 모듈은 `<feature>/ai/`(`XxxChangeModule`, `FindXxxTool`). 자세한 배치는 [ARCHITECTURE.md](ARCHITECTURE.md).
- 매퍼 XML은 `src/main/resources/mapper/<feature>/XxxMapper.xml`, namespace = 인터페이스 FQCN.
- 표·열은 snake_case 복수형 표(`execution_items`), PK는 `<단수>_id`. Java는 camelCase.
- 상태값은 Java enum + DB `VARCHAR` + `CHECK (col IN (...))`. enum 이름이 그대로 저장된다(typeHandler 없음).
- 모든 문서·주석·커밋 메시지는 한국어. 이름(클래스·열)은 영어.

## MyBatis Mapper XML

- XML 59개. `resultMap` 명시가 기본(56개 파일), `resultType`은 단순 조회에 섞여 쓴다. `mapUnderscoreToCamelCase` 설정 없음 — 열↔필드 대응은 resultMap에 적는다.
- id 접두 관례: `find*`(조회, 압도적 다수) · `insert*` · `update*` · `delete*`/`softDelete*` · `count*` · `lock*`(`FOR UPDATE`) · `mark*`(상태 전이) · `upsert*`(`ON DUPLICATE KEY UPDATE`) · `claim*`/`renewLease*`/`finish*`(worker 임대).
- 사용자 데이터를 읽고 쓰는 문에 `user_id = #{userId}` 조건(소유권). 물리 FK 없음.
- 낙관적 잠금: `SET version = version + 1 … WHERE … AND version = #{version}`, 영향 행 0이면 서비스가 409(`VERSION_CONFLICT`).
- 상태 전이: `WHERE status = 'PROPOSED'` 같은 조건부 UPDATE, 또는 `lock*`(`FOR UPDATE`, 42곳)로 잡고 확인 후 갱신.
- 생성 키: `useGeneratedKeys="true" keyProperty="…"`.
- JSON 열은 `LONGTEXT`/`TEXT` + `CHECK (JSON_VALID(...))`, Java에서는 문자열로 받아 ObjectMapper로 읽는다.
- 마이그레이션: `docs/sql/YYYY-MM-DD-<주제>.sql`. 추가 전용·재실행 가능(`IF NOT EXISTS`), 되돌리기 문은 파일 끝에 **주석으로**. 실제 사용자 데이터가 든 덤프는 저장소 밖 `<저장소 부모>/db-backup/`(`docs/sql/backup/README.md`).

## Jackson (ObjectMapper) 주입 규칙

- Spring Boot 4 기본은 Jackson 3(`tools.jackson`)이지만 코드는 **클래식 Jackson 2**(`com.fasterxml.jackson.databind.ObjectMapper`)를 쓴다. `common/config/JacksonConfig`가 그 빈을 하나 등록한다(`WRITE_DATES_AS_TIMESTAMPS` 끔 → 날짜는 ISO 문자열).
- 새 코드는 이 빈을 생성자 주입(`private final ObjectMapper objectMapper`)한다.
- 아직 `new ObjectMapper().findAndRegisterModules()`로 직접 만드는 곳이 39개 파일에 남아 있다. **일괄 치환 금지** — 로컬 매퍼는 `FAIL_ON_UNKNOWN_PROPERTIES`가 켜져 있고 공용 빈은 꺼져 있어, 바꾸면 모르는 필드를 조용히 버린다. 한 곳씩, 그 경로가 엄격함에 기대는지 확인하고 교체한다. 기대면 그 읽기에만 `objectMapper.readerFor(...).with(FAIL_ON_UNKNOWN_PROPERTIES)`. 특히 `PlanJudgmentService`·`PeriodPlanDraftGenerator`(모델 출력 과다생성 검증). 근거: `docs/product/07-ideas.md` "로컬 ObjectMapper를 공용 빈으로 정리".

## 시각·트랜잭션

- 현재 시각은 주입받은 `Clock`(UTC)으로. 사용자 시간대 변환은 쓰는 곳에서.
- 쓰기 서비스 메서드는 `@Transactional`, 조회는 `@Transactional(readOnly = true)`. 다른 트랜잭션 안에서만 불려야 하는 하위 쓰기는 `propagation = MANDATORY`.
- 모델 호출은 트랜잭션 밖에 둔다(예: `PlanDraftService`의 `TransactionTemplate`).

## 테스트 구조

- `src/test/java/...` 가 main과 같은 패키지 구조. 테스트 클래스 208개.
- 종류: `@SpringBootTest` 통합 58 · Mockito 단위(`@ExtendWith(MockitoExtension.class)`) 40 · `@WebMvcTest` 1 · Timefold `ConstraintVerifier` 1. `@DisplayName`은 한국어 문장(185곳).
- 통합 테스트 DB는 사용자 DB `memo`가 아니라 스키마만 복제한 **`memo_test`**(`bash scripts/test-db/prepare-memo-test.sh`, 스키마가 바뀌면 다시). `support/TestDatabaseIsolationTest`가 매 실행 확인한다. `-PtestDbUrl=`로 바꿀 수 있다.
- 테스트 중 백그라운드 worker 전부 끔(분석·정리·교재 조회·사진 정리). 업로드 경로도 `build/test-materials`.
- `-PexcludeDbTests`: 실제 테이블에 SQL을 직접 검증하는 테스트 묶음을 뺀다(CI에 전체 스키마가 없어서). 목록을 늘리면 `build.gradle` 주석의 개수도 같이 고친다.
- 실제 모델·외부 웹을 부르는 검증은 테스트가 아니라 `scripts/ai-baseline/*.py`.
- CI: `.github/workflows/ci.yml` — 모든 브랜치 push·PR, MySQL 8 서비스, `AI_PROVIDER=none`.

## 브랜치·커밋

- 통합 브랜치는 `dev-playground`. 작업은 `feat/<주제>`·`fix/<주제>` 브랜치(관찰된 브랜치 대부분, `docs/`·`chore/`도 있음)(필요하면 `wt/` 아래 git worktree)에서 하고 합친다.
- 합침 커밋: `merge: <브랜치>(<한 줄 요약>)를 dev-playground에 합침`.
- 커밋 제목: `<type>(<scope>)?: <한국어 문장>` — type은 `feat`·`fix`·`docs`·`refactor`·`chore`·`merge`. 문장은 "~한다/~를 둔다" 서술형이 흔하다.
- 확정된 변경은 관련 `docs/product/*` 문서와 `99-changelog.md`를 함께 고친다. 미확정 아이디어는 `07-ideas.md`에만(README "문서 수정 규칙").
- **공개 저장소**. `.githooks/pre-push`가 `scripts/check-private-names.sh`로 비공개 이름(저장소 밖 `.private-names` 목록)을 막는다. 켜기: `git config core.hooksPath .githooks`.

## 개행 (`.gitattributes`)

- `*.sh`, `.githooks/*`, `/gradlew` → `eol=lf`(CRLF로 받으면 Git Bash가 `$'\r'`로 죽는다). `*.bat` → `crlf`. `*.jar` → binary.
- 그 밖의 텍스트는 지정 없음 — 각자의 `core.autocrlf`를 따른다.

## 문서 레이어 유지 (docs/context)

변경이 `docs/context/SCHEMA.md` / `API.md` / `DOMAIN.md`의 기술을 무효화하면,
**같은 커밋에서** 해당 파일을 갱신한다. 갱신하지 않으면 단계 미완료로 본다.

- 표를 만들거나 지우는 마이그레이션 → `SCHEMA.md`의 `### 표` 절
- 매핑 애너테이션 추가·삭제·경로 변경 → `API.md`의 행(prefix는 클래스 줄)
- 상태 enum 값·전이 규칙 변경 → `DOMAIN.md`
- 확인: `bash scripts/check-context-layer.sh` (표 집합·엔드포인트 수·분량 상한 대조, 어긋나면 exit 1). CI·훅 연결은 아직 안 했다.
