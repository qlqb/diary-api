# 11. 기간형 계획 (plan_versions)

> 상태: 단계 1 착수 (2026-08-23)
> 선행: 자료 소유 구조 전환(api `0d2a1b9`), 레거시 실행 모델 제거(api `4e6c354`)
> 이 문서가 기간형 계획의 기준이다. 대화로 오간 인수인계 v1~v3은 이 문서로 대체한다.

## 0. 무엇을 만드는가

지금 앱은 "다음에 뭘 공부할까"에 **단일 topic 하나**로 답한다. 프로젝트 화면은
`IN_PROGRESS` 하나 또는 첫 `NOT_STARTED`를 골라 "지금/다음"으로 보여준다. 목차 순서를
학습 순서로 쓰는 구조다.

```text
기존   목차 순서대로 현재 포인터 → 다음 포인터
새것   기간을 정하고, 그 기간에 무엇을 할지 결정하고, 나중에 결과와 비교한다
```

기간은 하루도 되고 한 달도 된다. `start_date ~ end_date` 하나로 전부 표현한다.

### 네 층의 책임

```text
Project(courses)  왜 하는가. 자료·토픽·대화 맥락을 소유한다
PlanVersion       그때 무엇을 하기로 했는가. 불변 스냅샷. UPDATE하지 않는다
ExecutionItem     지금 무엇을 언제 하는가. 현재 상태의 유일한 원본
ExecutionRecord   실제로 어떻게 되었는가
```

인수인계 v3은 여기에 `DaySetting`을 다섯 번째 층으로 두었으나 뺐다. 근거는 §1-4 참고.

### 만들지 않는 것

| 안 만든다 | 이유 |
|---|---|
| **재계획 (version+1)** | 조정 액션(`CREATE/REDUCE/MOVE/DROP`)은 이미 있다. 없는 것은 기간 전체를 재판단하는 오케스트레이션과 diff 검토 UI다. 그리고 재계획이 무엇을 해야 하는지는 계획을 몇 번 써봐야 안다 |
| `work_items` / `plan_items` | 쪼개기는 `source_execution_item_id`가 이미 담당한다. `plan_items`는 2026-08-23에 제거했다 |
| `plan_plan_items` (N:M) | 계획을 살아 있는 상자로 만들면 월간↔주간 동기화 규칙이 따라온다. 스냅샷이면 그 문제가 생기지 않는다 |
| `target_month` / `target_week` | 같은 날짜에서 파생되는 값이라 하나만 안 고치면 즉시 모순이 생긴다 |
| `plan_versions.status` | 기간이 지나면 자연히 과거가 된다. "지금 유효한 계획"은 날짜로 계산된다 |
| `[더 제안받기]` 버튼 | 기존 proposal에 어떻게 합칠지 정의가 없다. 부족하면 초안을 다시 만든다 |
| 기간 길이별 3종 화면 | 목록 + 기존 주간 시간표로 시작한다 |
| RRULE / 루틴 | 수업 시간표를 매번 손으로 넣게 되면 그때 |
| 주당 시간 직접 입력 슬라이더 | 사용자가 자기 가용시간을 정확히 안다고 가정하는 것인데 대개 모른다. 프리셋 3개로 시작한다 |
| 강도 자동 보정 | 회고 결과로 AI가 강도를 낮추지 않는다. 정보만 주고 판단은 사용자가 한다 |
| 일자별 시간 배분 표시 | "월요일 3시간"은 배치 문제이지 강도 문제가 아니다 |
| 사용자 설정에 기본 강도 저장 | §5-1-2의 직전 계획 승계로 해결된다. 컬럼도 화면도 만들지 않는다 |

---

## 1. v3에서 정정된 것 (조사로 확인)

착수 전 조사(2026-08-23, 실 DB 대조)에서 v3의 전제 다섯 개가 사실과 달랐다. 아래가 확정
내용이고, 이후 절은 이를 반영해 쓰였다.

### 1-1. `chk_execution_items_placement`는 이미 있다 — 새로 만들지 않는다

v3 §3-3은 이 제약을 추가하라고 했으나 DB에 이미 존재하고, 더 강하다.

```sql
-- 현재 걸려 있는 것 (v3 제안분 + 아래 두 조건)
... and cast(scheduled_start_at as date) = scheduled_date
    and scheduled_start_at < scheduled_end_at
```

v3은 "날짜 일치는 타임존과 얽히니 CHECK로 걸지 않는다"고 했지만 이미 걸려 있고 위반 행은
0건이다. **§3-3의 제약 추가는 삭제한다.** 이름 중복으로 실패할 뿐 아니라, 이름만 바꿔
교체하면 기존 보장이 약해진다.

`planning_*` 전용 제약(§3-3)만 새로 추가한다.

### 1-2. FK 미사용은 "신규 테이블" 컨벤션이다

v3은 "FK 미사용이 이 프로젝트 컨벤션"이라 했으나 스키마에 FK가 20여 개 있다. 정확히는
`2026-08-16-material-store.sql`에 명시된 **그 이후 신규 테이블에 적용되는** 컨벤션이고,
그 이전 테이블(`execution_items` 등)은 FK를 쓴다.

**`plan_versions`는 그 이후이므로 FK를 걸지 않는다.** 소유권은 서비스 코드가 `user_id`로
검증한다.

### 1-3. `execution_records`의 컬럼은 `outcome`이다

`result_type`이 아니다. 값은 `COMPLETED / PARTIAL / NOT_DONE`.

부분 완료 잔여분은 **`execution_records.remaining_execution_item_id`가 1급 근거**다.
DB가 강제한다.

```sql
chk_execution_records_remaining CHECK (
  outcome='PARTIAL' and remaining_execution_item_id is not null
  or outcome<>'PARTIAL' and remaining_execution_item_id is null)
```

`execution_items.source_execution_item_id`도 같이 채워지지만 보조 근거로 쓴다.

### 1-4. `daily_plans`는 개명이 아니라 삭제했다

v3 §2는 `day_settings`로 승격시키려 했다. 유일한 호출자였던 `ScheduleBlockActionService`가
레거시 정리로 사라지면서 쓰는 코드가 없어졌고, 1행짜리 빈 테이블에 새 이름만 붙이면
"이름은 새것인데 아무도 안 쓰는" 상태가 굳는다. 하루 설정이 실제로 필요해지면 그때
`day_settings`로 새로 만든다. **v3 §2(단계 0)는 폐기됐다.**

### 1-5. 재계획 제외의 근거를 바꾼다

v3 §7-1은 "`apply()`가 항상 새 항목을 만들어 중복이 생긴다"를 근거로 들었으나 틀렸다.
`AiProposalService.apply()`에 조정 경로가 이미 있고 `ProposalOperation`은
`CREATE / REDUCE / MOVE / DROP`이다. 6개 액션 중 4개가 있고 KEEP은 제안에서 빼면 된다.

**그럼에도 1차에서 제외한다.** 없는 것은 액션이 아니라 (가) 기간 전체를 재판단하는
오케스트레이션과 (나) 무엇이 어떻게 바뀌는지 보여주는 diff 검토 UI다. 그리고 재계획이
무엇을 해야 하는지는 계획을 몇 번 써봐야 안다.

```text
1차   새 계획 확정만. plan_key = 새 UUID, version = 1 (항상)
      같은 기간에 또 만들면 별개 계획 두 개다 — §5-3이 목록을 반환하므로 화면은 깨지지 않는다
```

`version`이 항상 1이라 `MAX(version)+1` 경합이 없다. `UNIQUE(plan_key, version)`은 두되
**재시도 로직은 만들지 않는다** — `@Transactional` 안에서
`DataIntegrityViolationException`을 잡아도 트랜잭션이 이미 rollback-only라 같은 트랜잭션 내
재시도는 실패한다. 그 함정을 애초에 만들지 않는다.

---

## 2. 단계 1 — DB 마이그레이션

`docs/sql/2026-08-24-plan-versions.sql`. 수동 적용, 신규 테이블 FK 미사용,
VARCHAR + CHECK, 소유권은 서비스 코드에서 `user_id`로 검증.

### 2-1. `plan_versions`

```sql
CREATE TABLE plan_versions (
    plan_version_id    BIGINT        NOT NULL AUTO_INCREMENT,
    user_id            BIGINT        NOT NULL,
    plan_key           CHAR(36)      NOT NULL,
    version            INT           NOT NULL DEFAULT 1,
    start_date         DATE          NOT NULL,
    end_date           DATE          NOT NULL,
    title              VARCHAR(200)  NOT NULL,
    goal_summary       VARCHAR(1000) NULL,
    items_snapshot     LONGTEXT      NOT NULL,
    source_proposal_id BIGINT        NULL,
    confirmed_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (plan_version_id),
    UNIQUE KEY uq_plan_versions_key_version (plan_key, version),
    UNIQUE KEY uq_plan_versions_proposal (source_proposal_id),
    INDEX idx_plan_versions_user_period (user_id, start_date, end_date),
    CONSTRAINT chk_plan_versions_period CHECK (start_date <= end_date),
    CONSTRAINT chk_plan_versions_snapshot CHECK (JSON_VALID(items_snapshot))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

이 테이블은 UPDATE하지 않는다. 현재 상태는 `execution_items`가 유일하게 소유하고, 여기에는
"그때 무엇을 하기로 했는가"만 남는다. `items_snapshot`의 값 복사는 이중 원본이 아니라
의도적인 역사 보존이다.

`UNIQUE (source_proposal_id)`는 같은 제안을 두 번 확정하는 것을 DB에서도 막는다.
NULL은 여러 번 허용된다(MariaDB 표준 동작).

### 2-2. `execution_items` 컬럼 추가

```sql
ALTER TABLE execution_items
    ADD COLUMN plan_version_id     BIGINT NULL AFTER course_id,
    ADD COLUMN planning_start_date DATE   NULL AFTER scheduled_date,
    ADD COLUMN planning_end_date   DATE   NULL AFTER planning_start_date;

CREATE INDEX idx_execution_items_plan_version
    ON execution_items (user_id, plan_version_id);
CREATE INDEX idx_execution_items_planning_range
    ON execution_items (user_id, planning_start_date, planning_end_date);
```

- `plan_version_id` — 이 조각을 **처음 만들어낸** 계획 확정. 생성 출처이며 이후 바뀌지 않는다.
  NULL이 정상값이다(직접 추가, AI 단건 추천 승인). "현재 어느 계획에 속하는가"는 이 컬럼이
  답하지 않는다. 그건 스냅샷이 답한다.
- `planning_start_date` / `planning_end_date` — 날짜를 아직 안 정한 조각의 목표 기간.
  `scheduled_date`의 파생 중복이 아니라, `scheduled_date`가 없는 조각의 독립 정보다.
  이게 없으면 8일 이상 계획을 확정한 직후 range 조회에서 전부 사라진다.

### 2-3. `planning_*` 정합성 CHECK

**`chk_execution_items_placement`는 이미 있으므로 건드리지 않는다**(§1-1). 아래만 추가한다.

```sql
ALTER TABLE execution_items
    ADD CONSTRAINT chk_execution_items_planning_range CHECK (
        (placement_type = 'UNSCHEDULED'
         OR (planning_start_date IS NULL AND planning_end_date IS NULL))
        AND
        ((planning_start_date IS NULL AND planning_end_date IS NULL)
         OR (planning_start_date IS NOT NULL AND planning_end_date IS NOT NULL
             AND planning_start_date <= planning_end_date))
    );
```

이유는 조회 중복이 아니다(SQL의 OR은 행을 중복 반환하지 않는다). `scheduled_date`와
`planning_*`가 동시에 채워지면 "이 항목의 기간은 언제인가"에 답이 둘이 되고, 둘이 어긋났을 때
어느 쪽이 진실인지 알 방법이 없다. 현재 기간의 이중 원본을 막는 제약이다.

### 2-4. `ai_proposals`에 계획 기간 보존

```sql
ALTER TABLE ai_proposals
    ADD COLUMN plan_start_date DATE NULL,
    ADD COLUMN plan_end_date   DATE NULL;

ALTER TABLE ai_proposals
    ADD CONSTRAINT chk_ai_proposals_plan_period CHECK (
        (plan_start_date IS NULL AND plan_end_date IS NULL)
     OR (plan_start_date IS NOT NULL AND plan_end_date IS NOT NULL
         AND plan_start_date <= plan_end_date)
    );
```

초안 생성 시점의 기간을 서버가 보존한다. **확정 요청은 기간을 받지 않는다** — 클라이언트가
다시 보내면 초안과 다른 기간으로 확정될 수 있고, 그러면 스냅샷의 기간과 항목의 `planning_*`가
어긋난다. 계획 경로가 아닌 제안(단건 추천 등)은 두 값이 NULL이고, 그런 제안에 confirm을
호출하면 400으로 거절한다.

### 2-6. 계획 강도 컬럼

`docs/sql/2026-08-24-plan-intensity.sql`. 단계 1 마이그레이션이 이미 적용·커밋된 뒤에
정해진 변경이라 별도 파일로 낸다 — 적용이 끝난 마이그레이션은 고치지 않는다.

```sql
ALTER TABLE ai_proposals
    ADD COLUMN plan_intensity      VARCHAR(20) NULL,
    ADD COLUMN plan_target_minutes INT         NULL;

ALTER TABLE ai_proposals
    ADD CONSTRAINT chk_ai_proposals_plan_intensity CHECK (
        plan_intensity IS NULL OR plan_intensity IN ('LIGHT', 'NORMAL', 'FOCUSED'));

ALTER TABLE plan_versions
    ADD COLUMN intensity      VARCHAR(20) NULL,
    ADD COLUMN target_minutes INT         NULL;
```

`ai_proposals`에 두는 이유는 기간 컬럼(§2-4)과 같다 — 초안 생성 시점의 값을 서버가
보존해야 확정 때 어긋나지 않는다.

**`plan_target_minutes`를 `intensity`와 따로 저장한다.** 프리셋 값이 나중에 바뀌어도
그때 AI가 실제로 받은 예산이 남아야 한다. `intensity`에서 매번 재계산하면 과거 계획의
근거가 소급 변조된다.

`plan_versions`의 두 컬럼은 `items_snapshot`과 같은 성격의 역사 기록이다. 회고에서
"집중으로 잡았는데 절반만 했다"를 판단하려면 그때 강도가 무엇이었는지 남아야 한다.

둘 다 NULL을 허용한다. 강도 없이 만든 계획이 있을 수 있다.

### 2-5. 배치 전이 규칙 (서비스 코드가 지킨다)

```text
UNSCHEDULED → DATE_ONLY / TIME_FIXED
    planning_start_date / planning_end_date 를 NULL 로 지운다

DATE_ONLY / TIME_FIXED → UNSCHEDULED    ← 두 경우를 반드시 구분한다
    (가) 계획 안에서 날짜만 해제
         planning_* 에 기간을 채운다. range 조회에 계속 잡힌다. 기본 동작.
    (나) 계획에서 빼고 미분류로 보내기
         planning_* 도 NULL. range 조회에서 사라진다. 별도 액션으로만.
```

(가)를 기본으로 하지 않으면 사용자가 날짜만 뗐는데 항목이 화면에서 증발한다.

**기간은 요청으로 받는다. 서버가 추론하지 않는다.**

```text
PATCH /api/execution-items/{id}/unschedule
body: { planningStartDate, planningEndDate }   // 둘 다 null이면 (나)
```

같은 날짜에 계획이 여럿 걸릴 수 있으므로(§5-3) 서버는 "그 계획의 기간"을 고를 수 없다.
어느 계획 맥락에서 날짜를 뗀 것인지는 화면이 안다.

---

## 3. 단계 2 — 도메인 / 스냅샷

### 3-1. `PlanVersionMapper`

```text
insert(planVersion)
findByIdAndUserId(planVersionId, userId)
findCoveringDate(userId, date)      그 날짜를 포함하는 계획 목록
findByPlanKeyAndUserId(planKey, userId)
```

**`update`와 `delete`를 만들지 않는다.** 불변성을 문서가 아니라 Mapper에 그 메서드가 없다는
사실로 강제한다.

`findCoveringDate`는 **기간이 짧은 순, 같으면 최근 확정 순**으로 정렬해 반환한다. 동률일 때
`plan_version_id DESC`로 한 번 더 끊는다 — 확정 시각이 같은 두 계획이 호출할 때마다 다른
순서로 나오면 프로젝트 화면의 대표 계획이 깜빡인다.

### 3-1-1. `courseId` 필터는 SQL이 아니라 서비스에 둔다

`PlanVersionService.findCoveringDate(userId, date, courseId)`가 SQL 결과를 걸러낸다.

조건이 JSON 배열 안 원소의 속성이라 어차피 인덱스를 타지 못하고, MariaDB의 JSON 함수로
배열 원소를 비교하려면 질의가 읽기 어려워진다. 한 사용자의 특정 날짜를 덮는 계획은 많아야
서너 개이므로 SQL이 좁힌 뒤 걸러도 충분하다. `filter`는 순서를 보존하므로 대표 계획 선택
규칙은 그대로다 — 이 점은 테스트로 고정했다(`PlanVersionServiceTest`).

그 프로젝트의 계획이 하나도 없으면 **빈 목록**을 돌려준다. 다른 프로젝트 계획으로 대신
채우지 않는다.

### 3-1-2. 스냅샷 검증은 `PlanSnapshotCodec`이 한다

스냅샷은 한 번 쓰면 고칠 수 없으므로(update가 없다) 잘못된 값은 나중에 바로잡는 것이 아니라
애초에 저장되지 않아야 한다. `toJson`이 세 가지를 거부한다.

```text
executionItemId가 없는 항목    회고에서 대조할 키가 없어 영원히 판정 불가가 된다
같은 executionItemId 중복      회고에서 한 항목이 두 줄로 잡힌다
빈 스냅샷                      회고가 전부 "계획 밖에서 한 일"이 된다
```

`fromJson`은 모르는 필드를 무시한다(`@JsonIgnoreProperties(ignoreUnknown = true)`).
스냅샷은 몇 달 뒤에도 읽혀야 하고, 필드가 늘어난 뒤 저장된 JSON 때문에 과거 계획의 회고가
통째로 막히면 안 된다. 같은 이유로 `priority`는 enum이 아니라 String으로 담는다.

### 3-2. `items_snapshot` JSON

```json
[
  {
    "executionItemId": 123,
    "title": "연결 리스트 삭제 예제 따라 치기",
    "expectedMinutes": 40,
    "priority": "MUST",
    "courseId": 6,
    "courseTitle": "자료구조",
    "topicId": null,
    "placementType": "TIME_FIXED",
    "scheduledDate": "2026-08-25",
    "scheduledStartAt": "2026-08-25T14:00:00",
    "scheduledEndAt": "2026-08-25T14:40:00",
    "planningStartDate": null,
    "planningEndDate": null,
    "reason": "포인터를 이미 아니까 개념보다 구현부터 보는 게 빠릅니다"
  }
]
```

- `executionItemId`는 **필수**다. 회고에서 현재 상태와 대조하는 유일한 키다.
- 시각까지 저장해야 "몇 시에 하기로 했었나"를 회고할 수 있다.
- `courseTitle`은 표시용 복사본. 프로젝트 이름이 바뀌어도 스냅샷은 그때 이름을 유지한다.
- **이 JSON은 현재 `execution_items`와 동기화하지 않는다.**

---

## 4. 단계 3 — 조회 (계획 기능보다 먼저 고쳐야 하는 버그)

기존 `findByUserIdAndDateRange`(`ExecutionItemMapper.xml`)는 `scheduled_date BETWEEN`이라
`UNSCHEDULED` 항목을 **한 건도 잡지 못한다.** 현재 UNSCHEDULED 행이 0건이라 증상이 안 났을
뿐이고, 이 상태로 계획 확정을 만들면 8일 이상 계획 확정 직후 화면에서 모든 항목이 사라진다.

```sql
<select id="findByUserIdAndPlanningRange">
    SELECT * FROM execution_items
    WHERE user_id = #{userId}
      AND is_deleted = 0
      AND (
            scheduled_date BETWEEN #{startDate} AND #{endDate}
         OR (scheduled_date IS NULL
             AND planning_start_date &lt;= #{endDate}
             AND planning_end_date   &gt;= #{startDate})
          )
    ORDER BY scheduled_date IS NULL,   -- 배치된 항목 먼저
             scheduled_date ASC, order_index ASC, execution_item_id ASC
</select>
```

**기존 `findByUserIdAndDateRange`는 지우지 않는다.** 주간 시간표처럼 "배치된 것만" 보는
화면이 있다.

```text
GET /api/execution-items/range?startDate=&endDate=                          기존(기본값 false)
GET /api/execution-items/range?startDate=&endDate=&includeUnscheduled=true  계획 화면
```

### 4-1. 겹침 판정은 네 가지 모양을 다 잡아야 한다

```text
planning_start <= endDate AND planning_end >= startDate
```

"계획 기간이 조회 범위 안에 들어온다"가 아니라 **"두 구간이 조금이라도 만난다"**이다.
조회 범위를 2026-08-24~30이라 할 때:

| 모양 | 계획 기간 | 잡혀야 하나 |
|---|---|---|
| 안에 들어옴 | 08/26~08/28 | 예 |
| 범위를 완전히 포함 | 08/01~08/31 | 예 ← 놓치기 쉽다 |
| 앞쪽만 걸침 | 08/20~08/25 | 예 |
| 뒤쪽만 걸침 | 08/29~09/05 | 예 |
| 하루만 닿음 | 08/10~**08/24** | 예 |
| 하루 차이로 빗나감 | 08/10~08/23 | 아니오 |
| planning_* 없음 | — | 아니오 (§2-5 (나) 미분류) |

두 번째를 놓치면 "8월 한 달 계획"이 이번 주 화면에서 통째로 사라진다. 조건을 범위 포함으로
잘못 쓰면 정확히 그 케이스만 빠지고 나머지는 다 맞게 동작해서, 눈으로는 한참 못 찾는다.

`ExecutionItemPlanningRangeMapperTest`가 위 일곱 줄을 그대로 검증하고, **같은 행을 기존
질의에 물어 안 잡히는 것까지 확인한다.** 신규 질의가 잡는 것만 보면 주간 시간표에 날짜 없는
조각이 흘러드는 회귀를 못 잡는다.

---

## 5. 단계 4~5 — 초안 · 확정 · 회고

### 5-1. 초안 생성

```text
POST /api/plans/draft
body: {
  startDate, endDate,             필수. 최대 31일
  intensity,                      선택. LIGHT / NORMAL / FOCUSED
  title, instruction, courseIds   선택
}
```

기존 `PlanningAgentService.createDraft`는 `recommendationId`가 필수라 여러 프로젝트를
아우를 수 없다. **기존 경로는 건드리지 않고** 새 진입점을 추가한다. `planKey`는 받지 않는다.

프리셋(`[오늘] [내일] [앞으로 7일] [이번 주] [이번 달] [기간 선택]`)은 화면이 날짜로 변환해
보낸다. 서버는 프리셋을 저장하지 않는다. 31일 초과는 400으로 거절한다 — 그 이상은 계획이
아니라 목표에 가깝다.

**클라이언트는 분 단위를 보내지 않는다.** 서버가 `target_minutes`를 계산해 `ai_proposals`에
저장하고 AI에 전달한다. 프리셋 계산은 서버가 소유한다. 확정 요청은 기간과 마찬가지로 강도도
받지 않고 `ai_proposals`에서 읽어 `plan_versions`에 복사한다.

**항목 수 상한은 전역 상수를 올리지 않는다.** `AiProposalService.MAX_ITEMS = 5`는 두 곳에서
강제된다(`createFromItems`의 합계 검증, `validateAndNormalize`). 두 곳 모두 `maxItems`
파라미터를 받고, 기존 시그니처는 5를 넘기는 오버로드로 유지한다 — 한쪽만 고치면 통과 후
거절되거나 그 반대가 된다.

```text
일반 AI 제안       5개 유지          ← 기존 동작 그대로
기간 계획 전용     기간 ≤ 7일  → 15개
                   기간 8~31일 → 30개
```

주 제약은 개수가 아니라 시간이므로(§5-1-1), 개수는 **"이 정도면 확실히 뭔가 잘못됐다"는
선으로만** 남긴다.

`OpenAiConsultationClient`의 프롬프트에 박힌 5도 함께 파라미터화한다(조사에서 세 군데
확인됨). 코드 상한만 올리고 프롬프트가 5를 유지하면 장기 계획에서 항상 5개만 나온다.

프롬프트에는 개수가 아니라 시간을 명시하고, **고정값이 아니라 기준선으로 제시한다.**

> 이 기간의 기준 학습 시간은 약 {baseline}분이다. 사용자 상황(instruction, 고정 일정,
> 직전 회고)상 조정이 필요하면 조정하고 이유를 한 문장으로 밝혀라. 항목들의 예상 시간 합이
> 최종 목표 근처가 되게 하되 넘기지 마라.
> 항목을 잘게 쪼개 개수를 늘리지 마라. 한 번에 앉아서 할 만한 단위로 묶어라.
> 채울 내용이 없으면 억지로 채우지 말고 적게 제안하라.

마지막 줄이 중요하다. 시간도 상한으로 두지 않으면 모델이 채우려 든다.

**응답에서 `targetMinutes`와 `targetMinutesReason`을 받는다.**

```text
ai_proposals.plan_target_minutes   AI가 정한 최종값 (기준선이 아니다)
ai_proposals.plan_intensity        사용자가 고른 값 그대로
targetMinutesReason                초안 응답으로 전달만 한다 — 영속하지 않는다
```

이유를 저장하지 않는 근거: 확정 이후에는 스냅샷의 항목 합이 사실을 말한다. "왜 6시간으로
잡았나"는 초안을 검토하는 순간에만 필요한 정보이고, 그 순간이 지나면 계획 자체가 답이다.

기준선은 컬럼으로 남기지 않는다 — `intensity`와 일수로 언제든 다시 계산할 수 있다. 다만
프리셋 숫자가 바뀌면 과거 계획의 기준선은 복원되지 않는다. 최종값(`target_minutes`)만
보존하면 충분하다고 보고 받아들인 손실이다.

### 5-1-1. 계획 강도 — 통제 대상을 개수에서 시간으로 옮긴다

개수로 통제하지 않는 이유는 둘이다. **개수는 사용자가 판단할 수 있는 단위가 아니다**
("이번 달 계획 몇 개?"에는 근거가 없어 아무 숫자나 고르게 된다). 그리고 **개수 상한은
게이밍된다** — AI가 "연결 리스트 삭제"를 "개념 읽기 / 코드 보기 / 직접 짜보기"로 쪼개면
3개가 되고, 부하는 같은데 관리할 것만 늘어난다.

시간은 쪼개도 늘어나지 않는다. 40분을 20분 둘로 나눠도 합은 같다.

```text
사용자가 정하는 것   강도 (프리셋 3개)
AI가 받는 것         기준선 (기간 × 강도) — 고정값이 아니다
AI가 정하는 것       최종 목표 시간 + 조정 이유
개수 상한            폭주 방지선으로만 남긴다
```

프리셋은 **기준선**이다. 숫자에 실사용 근거가 없으므로(§8-1) 그것을 최종값으로 강제하면
근거 없는 값이 계획을 지배한다. AI는 instruction·고정 일정·직전 회고를 보므로 프리셋보다
많은 정보를 갖는다. 그래서 프리셋은 출발점만 제공하고 조정 권한은 AI에게 준다.
코드에서도 이름이 `PlanIntensity.baselineMinutes(days)`이지 `targetMinutes`가 아니다.

**주당 시간을 기간에 그대로 비례시키지 않는다.** `집중 = 주 18시간`을 30일에 적용하면
77시간이 되는데, 4주 내내 최고 강도를 유지한다는 뜻이라 지켜지지 않는다. 반대로 하루짜리는
2.6시간이 되어 집중이라기엔 약하다. 기간 구간별로 다르게 해석한다.

```text
                1~2일 (일 단위)   3~14일 (주 단위)   15~31일 (주 단위, 지속 가능선)
가볍게            90분/일            240분/주            200분/주
보통             180분/일            600분/주            480분/주
집중             240분/일           1080분/주            840분/주
```

```text
1~2일    target_minutes = daily × 일수
3~14일   target_minutes = round(weekly × 일수 ÷ 7)
15~31일  target_minutes = round(longTerm × 일수 ÷ 7)
```

```text
집중 + 1일    → 240분
집중 + 7일    → 1,080분 (18시간)
집중 + 30일   → 3,600분 (60시간)   ← 선형이면 77시간
보통 + 7일    → 600분 (10시간)
```

**이 숫자들은 추정이다.** 실사용 근거가 없으므로 `PlanIntensity` enum 한곳에 모아 나중에
값만 고칠 수 있게 한다. 구간 경계(2일 / 14일)도 같은 곳에 둔다.

```java
// 값은 실사용 후 조정한다. 구조를 바꾸지 않고 숫자만 고칠 수 있어야 한다.
LIGHT   (90, 240, 200),
NORMAL  (180, 600, 480),
FOCUSED (240, 1080, 840);
```

구간 경계에서 예산이 역전한다(하루를 늘렸는데 예산이 줄어든다). 알려진 값과 판단은 §8-1에
있다.

프리셋은 셋을 넘기지 않는다. 기본값은 `보통`이고, §5-1-2의 승계 규칙이 우선한다.

**문구 규칙.** `가볍게`가 게으름으로 읽히지 않게 한다. 실패 프레이밍 금지 원칙이 여기에도
적용된다.

```text
금지   최소 / 느슨하게 / 저강도 / 무리하지 않는
사용   가볍게 / 보통 / 집중
```

선택지 아래 한 줄로 보조 설명을 둔다.

> 이번 기간에 어느 정도 시간을 쓸지 정합니다. 나중에 조정할 수 있어요.

### 5-1-2. 강도 기본값 — 직전 계획에서 이어받는다

`intensity`가 없으면 서버가 이렇게 정한다.

```text
1. 가장 최근 확정한 plan_version의 intensity   (confirmed_at DESC LIMIT 1)
2. 그것도 없으면 NORMAL
```

**별도의 설정 컬럼이나 설정 화면을 만들지 않는다.** `plan_versions`에 이미 저장하므로 최근
한 건을 읽으면 된다.

이 방식이 "사용자 기본값 저장"보다 나은 이유: 사용자가 강도를 바꾸면 그것이 자동으로 다음
기본값이 된다. 설정을 따로 고치러 갈 필요가 없다. 한 번 `집중`으로 올리면 계속 집중으로
나오고, `보통`으로 내리면 그게 이어진다.

**배치 정책.** `SchedulePreviewService`는 8일 이상을 거부하지 않고 조용히 7일로 잘라낸다
(`SchedulePreviewService.java:94-96`. `horizonStart`도 과거면 조용히 today로 당긴다).
따라서 장기 계획에서는 호출하지 않는다.

```text
기간 ≤ 7일     computePreview() 호출 → TIME_FIXED까지 배치
기간 8일 이상  솔버 호출 안 함. 항목별로:
  날짜를 정한 것 → DATE_ONLY
  기간만 정한 것 → UNSCHEDULED + planning_start/end_date = 계획 기간
```

`scheduling.horizon.max-days`를 늘리지 않는다.

### 5-2. 확정 — 한 트랜잭션

```text
POST /api/plans/proposals/{proposalId}/confirm
body: { editedItems, excludedItemIds, title, goalSummary? }
```

기간은 `ai_proposals.plan_start_date / plan_end_date`(§2-4)에서 읽는다.

```java
@Transactional
public PlanVersionResponse confirm(...) {
    // 0. proposal의 기간을 읽는다. NULL이면 400. 31일 초과 재검증.
    // 1. AiProposalService.apply() → execution_items 생성
    //    기간 > 7일이고 날짜 미정인 항목은 planning_start/end_date를 채운다
    // 2. 생성된 조각들로 items_snapshot 조립
    //    ai_proposal_items.created_item_id가 제안 항목별 생성 결과를 이미 기록한다
    // 3. plan_versions INSERT (plan_key = UUID.randomUUID(), version = 1)
    // 4. 생성된 execution_items에 plan_version_id 기록
}
```

4단계 UPDATE는 반드시 이 형태로 한다.

```sql
UPDATE execution_items
   SET plan_version_id = #{planVersionId}
 WHERE execution_item_id IN (...)
   AND user_id = #{userId}
   AND plan_version_id IS NULL     -- 출처는 한 번만 기록된다
```

**영향 행 수가 생성 항목 수와 다르면 예외를 던져 전체 롤백한다.** 이것이
`plan_version_id`의 불변성을 코드에서 강제하는 지점이다.

한 트랜잭션인 이유: 5개 중 3개만 생기고 실패하면 스냅샷은 5개라는데 실제로는 3개다.
회고에서 그 2개가 "계획했는데 배치 안 함"으로 잘못 읽힌다. 첫날부터 거짓 회고가 생긴다.

계획 없이 만드는 경로는 그대로 둔다 — 직접 추가와 AI 단건 추천 승인은 `plan_version_id`가
NULL이고 그게 정상이다. 기간 중 사용자가 직접 추가한 항목도 NULL이며, 회고가 스냅샷의
`executionItemId` 목록으로 대조하므로 "계획 밖에서 한 일"로 자연히 분류된다.

### 5-3. 조회 — 스냅샷을 계획 화면에 보여주지 않는다

```text
GET /api/plans?date=2026-08-25              그 날짜를 포함하는 계획 목록 (기간 짧은 순)
GET /api/plans?date=2026-08-25&courseId=6   그중 이 프로젝트 항목을 담은 계획만
GET /api/plans/{planKey}                    그 계획
```

같은 날짜에 8월 계획·이번 주 계획·오늘 계획이 동시에 걸릴 수 있다. **단건을 반환하지
않는다.** `courseId` 필터는 스냅샷 안에 그 `courseId` 항목이 하나라도 있는 계획만 남긴다.

**계획 화면의 항목 목록은 항상 현재 `execution_items`를 본다**(§4의 신규 조회).

의도적으로 받아들이는 결과가 하나 있다. **이번 주 항목을 다음 주로 옮기면 이번 주 계획
화면에서 사라진다.** 맞는 동작이다 — "이번 주에 뭐 하지"를 보는데 다음 주 것이 섞이면
안 된다. "어디 갔지"는 회고가 `이동됨`으로 답한다. 계획 화면과 회고 화면의 책임을 나눈 것이
이 설계의 요점이므로, 계획 화면에 스냅샷 조회를 넣지 않는다.

### 5-4. 회고 — 판정은 `status`가 결정한다

```text
GET /api/plans/{planVersionId}/review
```

**주 분류는 `status`가 단독으로 결정한다.** `execution_records`는 `status = DONE`일 때
완료와 일부 진행을 가르는 데만 쓴다. `status = PLANNED`면 기록이 무엇이든 "남아 있음" 또는
"미배치"다.

이 규칙이 필요한 이유는 실데이터에 있다. item 19는 `COMPLETED` 기록이 있는데 3초 뒤
`REOPENED` 이벤트로 되돌려져 `status = PLANNED`다. `reopen()`은 `execution_records`를
지우지 않으므로(설계상 이력을 남긴다) **현재 코드로도 재현된다.** 기록을 우선하면 되돌린
항목이 완료로 보인다.

**기록이 여러 건이면 `recorded_at DESC LIMIT 1`을 쓴다.** 응답에 `recordCount`를 포함해
여러 건이었다는 사실을 추적할 수 있게 한다.

| 스냅샷 | 현재 `status` | 최신 기록 | 주 분류 | 화면 문구 |
|---|---|---|---|---|
| 있음 | `DONE` | `COMPLETED` | 완료 | 했어요 |
| 있음 | `DONE` | `PARTIAL` | 일부 진행 | 일부 진행했어요 |
| 있음 | `DONE` | 없음 | 완료 | 했어요 |
| 있음 | `PLANNED`, 날짜 있음 | 무관 | 남아 있음 | 아직 남아 있어요 |
| 있음 | `PLANNED`, `UNSCHEDULED` | 무관 | 미배치 | 아직 시작하지 않았어요 |
| 있음 | `HOLD` | 무관 | 보류 | 잠시 멈춰뒀어요 |
| 있음 | `CANCELLED` 또는 `is_deleted=1` | 무관 | 제외 | 계획에서 뺐어요 |
| 없음 | 기간 내 존재 | 무관 | 계획 밖 | 계획 밖에서 한 일 |

**잔여분**은 `execution_records.remaining_execution_item_id`를 1급 근거로 추적한다
(§1-3). 그 id가 가리키는 항목이 스냅샷 밖에 있으면 "남은 분량"으로 분류한다.
`execution_items.source_execution_item_id`는 보조로만 쓴다.

회고 조회는 `is_deleted`를 강제하지 않는 전용 조회가 필요하다.

**`이동됨`은 주 분류가 아니라 부가 플래그다.** 완료했는데 다른 날 한 경우가 흔하므로 둘이
동시에 성립한다. 비교는 NULL-safe로 한다(MariaDB `<=>` 또는 Java `Objects.equals`) —
한쪽이 NULL일 때 `!=`는 UNKNOWN이 되어 false로 떨어진다.

```text
스냅샷 날짜 있음 → 현재 다른 날짜      moved      "다른 날로 옮겨서"
스냅샷 날짜 없음 → 현재 날짜 있음      scheduled  "날짜를 정해서"     ← 이동이 아니다
스냅샷 날짜 있음 → 현재 날짜 없음      unplaced   "날짜를 다시 뗐어요"
둘 다 없음 / 같음                      플래그 없음
```

`UNSCHEDULED → DATE_ONLY`는 **배치**이지 이동이 아니다. 계획대로 진행된 정상 경로이므로
"옮겼다"고 표현하면 사용자가 무언가 어긋났다고 읽는다.

미배치가 이 설계의 존재 이유다. 실행 조각을 날짜로 묶기만 하는 구조로는 "계획에 없었다"와
"계획했는데 배치를 안 했다"를 구분할 수 없다.

#### 5-4-1. 강도와 시간

회고 응답에 네 값을 포함한다.

```text
intensity          그때 고른 강도
targetMinutes      그때의 목표 시간
plannedMinutes     스냅샷 항목들의 예상 시간 합
completedMinutes   완료·일부 진행 항목의 실제 시간 합 (execution_records 기준)
```

**달성률이나 퍼센트로 표시하지 않는다.** 세 숫자를 나란히 보여주기만 한다.

```text
집중으로 잡았고, 15시간 중 9시간 30분을 했어요.
```

이 값은 다음 계획 초안의 입력으로 들어간다(§5-1 "직전 계획의 회고 요약"에 추가).
**AI가 강도를 자동으로 낮추지는 않는다** — 정보로만 주고 판단은 사용자가 한다.

```text
직전 계획: 집중(15시간 목표), 실제 9시간 30분 수행
```

---

## 6. 단계 6 — UI

### 6-1. 계획 만들기 — 강도는 평소에 접어둔다

강도는 초안 **생성 전에** 정해진다. 초안이 나온 뒤 강도를 바꾸는 것은 결국 다시 생성하는
것과 같으므로, 초안 화면에는 강도 변경을 두지 않는다. 기본값이 직전 계획에서
이어지므로(§5-1-2) 매번 고르게 하지도 않는다.

```text
계획 만들기
기간   [오늘] [내일] [앞으로 7일] [이번 주] [이번 달] [기간 선택]

집중 · 약 15시간                                    [강도 바꾸기]

[초안 만들기]
```

한 줄로 현재 강도와 **그 기간에 해당하는 계산된 시간**을 함께 보여준다. `집중`만 표시하면
이번 기간이 몇 시간인지 알 수 없다 — 한 달이면 60시간, 하루면 4시간이다.

`[강도 바꾸기]`를 눌렀을 때만 펼친다.

```text
  ( ) 가볍게    주 4시간쯤
  (•) 보통      주 10시간쯤
  ( ) 집중      주 18시간쯤
  이번 기간에 어느 정도 시간을 쓸지 정합니다. 나중에 조정할 수 있어요.
```

**주당 시간을 함께 보여줘야 고를 수 있다.** 라벨만으로는 `집중`이 얼마나 집중인지 모른다.

이력이 없는 첫 계획이면 접지 않고 펼친 상태로 시작한다. `보통`이 선택돼 있으므로 그냥
넘어가도 된다. 한 번 확정하면 그다음부터는 접힌 형태가 된다.

### 6-1-1. 초안 검토 — 시간 예산 게이지

```text
8월 24일 ~ 8월 30일
집중 · 목표 6h 30m          ████████████░░░   12h 30m / 6h 30m
알바 일정을 고려해 낮게 잡았어요
```

AI가 기준선을 조정했으면(`targetMinutesReason`이 있으면) 게이지 아래 한 줄로 이유를 보여준다.
조정하지 않았으면 그 줄은 없다.

체크를 풀면 게이지가 줄어든다. **사용자가 조정하는 대상이 개수가 아니라 부하가 된다.**
"6개"보다 "12시간 30분"이 판단 근거다. 체크 해제는 기존 `excludedItemIds`를 쓴다.

**게이지가 목표를 넘어도 경고하지 않는다.** 색만 바꾸고 문구는 두지 않는다 —
"초과했습니다"는 실패 프레이밍이고, 넘겨서 잡는 것도 사용자의 선택이다.

### 6-1-2. 프로젝트별 그룹핑

```text
자료구조                                        5h 20m   ☑
  ☑ 연결 리스트 삭제 예제 따라 치기        40분   8/25
    포인터는 이미 안다고 하셨으니 구현부터 보는 게 빠릅니다
  ☑ 과제 2번 구현                          60분   8/26

빅데이터분석                                    3h 00m   ☑
  ☑ ...

기타                                            1h 30m   ☑
  ☑ 병원 예약                              30분   8/27
```

30개를 평평하게 늘어놓으면 읽을 수 없다. 프로젝트 3~4개로 묶으면 각 그룹이 5~8개라 읽힌다.
그룹 헤더의 체크박스로 그룹 전체를 켜고 끈다 — "이번 주는 빅데이터 빼자"가 한 번에 된다.

`courseId`가 없는 항목은 `기타` 그룹으로 묶는다. **"미분류"라고 쓰지 않는다.**

### 6-1-3. 장기 계획은 접어서 시작

```text
기간 ≤ 7일     전부 펼침
8일 이상       오늘부터 7일 안에 걸치는 항목만 펼치고 나머지 그룹은 접기
```

한 달 계획 30개를 한 화면에 펼치면 압도된다. "이번 주 8개 + 나머지 22개 접힘"이면 지금
판단할 것만 보인다. 접힌 그룹도 헤더에 소계 시간과 항목 수를 보여준다.

### 6-2. 프로젝트 화면 상단 교체

`ProjectWorkspace.jsx:94`의 `find(IN_PROGRESS) ?? find(NOT_STARTED)`를 현재 계획 기준으로
바꾼다.

```text
자료구조
이번 계획  개강 전까지 3장까지 훑기 (8/24~8/30)
  다음     연결 리스트 삭제 예제 · 8/25 · 40분
```

대표 계획은 `GET /api/plans?date=오늘&courseId={이 프로젝트}` 결과의 **첫 번째**다.
서버 정렬을 그대로 따르고 화면이 재정렬하지 않는다. `courseId`를 반드시 넘긴다 — 안 넘기면
자료구조 화면에 기간이 더 짧은 영어 계획이 뜬다. 계획이 없으면
"아직 계획을 세우지 않았어요 · [계획 만들기]".

**`course_topics` / `topic_progress`는 삭제하지 않는다.** 역할이 진도표에서 참고 자료로
내려갈 뿐이다. 기본 접힌 영역으로 유지한다.

### 6-3. 진행률 % 제거

`ProjectsView.jsx:216`의 `round(learned/total*100)`을 없앤다. 자료를 추가하면 분모가 늘어
어제 60%가 오늘 35%가 된다. 사용자가 아무것도 잘못하지 않았는데 숫자가 내려가는 화면은
실패 프레이밍 금지 원칙 위반이다.

```text
자료구조
이번 계획: 3개 중 1개 완료
```

### 6-4. 문구 규칙

```text
금지   뒤처졌습니다 / 부족합니다 / 밀렸습니다 / 미완료 / 실패 / 못 함
       이행률 / 달성률 / 퍼센트 / 진도가 늦습니다
사용   아직 시작하지 않았어요 / 이번 기간에는 이것부터 / 여유가 있으면
       §5-4 표의 라벨. 개수와 목록으로 보여준다
```

`src/types/execution.js`의 `STATUS_LABEL` / `RESULT_TYPE_LABEL`을 재사용한다.

---

## 7. 커밋

```text
feat(plan): add plan_versions table and execution item planning range
fix(execution): include unscheduled items in planning range query
feat(plan): add period-based plan draft and confirm
feat(plan): add plan review comparing snapshot with actual
feat(ui): add period plan creation and review views
```

각 커밋 전: `./gradlew test` / `npx eslint src` / `npx vitest run` / `npx vite build`.

## 8. 사람에게 확인받을 지점

1. **단계 3 종료 후** — 새 range 조회가 `UNSCHEDULED` 항목을 실제로 반환하는지.
   DB에 `UNSCHEDULED` + `planning_*` 행을 직접 넣어 확인하고 정리한다. (완료: `9537b65`)
2. **단계 5 확정 후** — 계획을 하나 만들어 확정하고, 항목을 다른 날로 옮긴 뒤 화면이
   스냅샷이 아니라 현재 상태를 보여주는지, 회고에서 `이동됨`으로 잡히는지 확인받는다.
3. **§8-1의 구간 경계 역전** — 값을 조정할지 그대로 둘지.

### 8-1. ★ 구간 경계에서 예산이 역전한다 — 미결

기간을 하루 늘렸는데 예산이 줄어드는 구간이 **두 곳** 있다.

```text
days     LIGHT   NORMAL  FOCUSED
    1       90      180      240
    2      180      360      480
    3      103      257      463     ← 2일보다 적다
    7      240      600     1080
   14      480     1200     2160
   15      429     1029     1800     ← 14일보다 적다
   30      857     2057     3600
```

| 경계 | LIGHT | NORMAL | FOCUSED |
|---|---|---|---|
| 2일 → 3일 | **−42.8%** | −28.6% | −3.5% |
| 14일 → 15일 | −10.6% | −14.2% | −16.7% |

개정안은 14↔15일만 지적했으나 **2↔3일이 더 심하다.** `가볍게`로 2일 계획을 세우면 180분,
하루 늘려 3일로 하면 103분이다. 사용자가 날짜를 하나 늘렸다 줄였다 하면 숫자가 눈에 띄게
튄다.

원인은 일 단위 해석과 주 단위 해석의 기울기 차이다. `가볍게`의 일 단위는 90분/일인데
주 단위는 240분/주(≈34분/일)이라 2.6배 차이가 난다. `집중`은 240 대 154(≈1.6배)라 덜하다.
즉 **역전 폭은 프리셋마다 다르고, 가벼울수록 크다.**

세 가지 선택지가 있다.

```text
(가) 그대로 둔다
     1~2일 계획은 "오늘/내일 뭐 하지"라 성격이 다르고, 2일과 3일을 오가며 비교하는
     사용은 드물다고 보는 쪽. 코드가 가장 단순하다.

(나) 경계에서 보간한다
     3~4일 구간을 일 단위와 주 단위 사이로 선형 보간. 튀는 느낌은 사라지지만
     "구간별 해석" 규칙이 하나 더 늘고, PlanIntensity가 표가 아니라 함수가 된다.

(다) 경계값을 조정한다
     일 단위 값을 낮추거나(LIGHT 90 → 50) 주 단위를 올려(240 → 400) 기울기를 맞춘다.
     구조는 그대로 두고 숫자만 고치므로 가장 싸다. 다만 "가볍게 하루 = 50분"이
     의도한 강도인지는 별개 판단이다.
```

**현재는 (가)로 두고 개정안의 값을 그대로 구현했다.** 실사용 전에 숫자를 다시 만지는 것은
근거 없이 근거 없는 값을 바꾸는 일이라, 어느 쪽이 거슬리는지 써보고 정한다.
`PlanIntensityTest`가 위 표를 그대로 고정하고 있으므로, 값을 바꾸면 테스트가 먼저 깨져
변경이 눈에 보인다.

### 8-2. 강도 도입분 검증 목록

```text
target_minutes가 구간별로 다른 기준을 쓰는가 (1 / 2 / 3 / 14 / 15 / 30일)
구간 경계에서 값이 어떻게 움직이는가 (§8-1에 고정)
intensity 없이 요청하면 직전 계획의 강도를 이어받는가
확정 이력이 없을 때 NORMAL로 떨어지는가
확정 시 intensity와 target_minutes가 ai_proposals에서 plan_versions로 복사되는가
클라이언트가 confirm에 다른 intensity를 보내도 무시되는가
잘못된 intensity 값이 CHECK에 걸리는가
게이지 합계가 체크 해제와 함께 줄어드는가
그룹 헤더 체크가 하위 항목 전체를 토글하는가
```

## 9. 이번 범위 밖

```text
재계획 (version+1)                  §1-5. 오케스트레이션과 diff UI가 함께 필요하다
day_settings                        하루 설정이 실제로 필요해지면 새로 만든다
courses.goal_text / goal_deadline   외부 마감은 key_dates가 갖고 있고 "과목 완주"는 제목과
                                    거의 같은 정보다. "AI가 프로젝트 목표를 몰라 엉뚱한 걸
                                    추천한다"가 실제로 생기면 넣는다
topic_progress.prior_known          추천이 "이미 아는 걸 권한다"가 불편해진 뒤
복수 추천 (Learning Agent 배열화)     계획 초안이 이미 복수 항목이라 급하지 않다
자료 청크 · 위치 색인                buildMaterialExcerpt가 원문 앞 3,000자만 읽는 문제
work_items                          쪼개기가 실제로 불편해진 뒤
31일 초과 계획                       그 길이는 계획이 아니라 목표에 가깝다
course_id / material_type DROP      롤백 여지로 남겨둔다
CI의 -PexcludeDbTests               DROP 후 스키마 덤프를 넣고 제거한다
```

### 9-1. 조건부 유보 — 착수 조건을 함께 적는다

**실측 기반 예산 자동 계산**

```text
조건   execution_records의 완료 기록이 20건 이상 쌓였을 때
```

최근 4주 하루 평균 수행 시간 × 기간 일수 − 그 기간의 고정 일정(`TIME_FIXED`)으로 예산을
계산한다. 프리셋은 콜드 스타트용으로 남기고, 강도 선택은 "다르게 할래요"로 접는다.

```text
"최근 하루 평균 95분 하셨어요. 이번 주는 9시간 정도로 잡을게요."
                                              [다르게 할래요]
```

묻지 않고 알려준다. 바꿀 수 있게만 둔다.

근거: 강도 프리셋이 추정하려는 값은 "이 사람이 이 기간에 몇 시간 쓸 수 있나"다.
`execution_records`에 실제 수행 시간이 이미 쌓이므로, 데이터가 생기면 추정할 이유가 없다.
**강도 선택은 영구 기능이 아니라 콜드 스타트 장치다.**

비용: `target_minutes`를 서버가 소유하는 구조(§5-1)가 그대로 유지되므로 계산 근거만
교체하면 된다. API·스키마 변경 없음.

**조사 결과(2026-08-23)**: 가용시간 기반 계산은 `AvailabilityEstimateService` 재사용으로
즉시 가능하다 — 그 서비스는 배치할 항목을 인자로 받지 않고, `AvailabilityWindow`에
`durationMinutes()`가 이미 있다. 하지만 그 값의 출처가 하드코딩 기본 시간대(평일 19–22시,
주말 10–18시 = 주 1,860분)라 프리셋보다 정확하지 않다. 실측이 쌓이면 그쪽으로 간다.

실측 전환 시 **`min(실측 예산, 가용시간 추정)`**을 검토한다 — 실측은 수요(평소 얼마나
하나), 가용은 공급(이번 기간 얼마나 비나)이라 달력이 채워진 뒤에는 다른 것을 잡는다.

이것이 강도 조정 카드를 여러 개 만들지 않는 이유다. 예측이 정확해지면 물어볼 일 자체가
줄어든다. 조건 트리거를 늘리는 것보다 추정을 실측으로 바꾸는 쪽이 낫다.

**`limitDailyLoad`를 개수에서 분 기준으로**

```text
조건   하루에 항목이 4개 이상 배치되는 계획이 실제로 나올 때
```

`SchedulingConstraintProvider.limitDailyLoad`는 `ConstraintCollectors.count()`로 **항목
개수**를 세고 `DAILY_LOAD_SOFT_LIMIT = 3`을 넘으면 SOFT 페널티를 준다. 개수 페널티는
쪼개기로 게이밍된다 — 40분 하나를 20분 둘로 나누면 부하는 같은데 페널티에 가까워진다.
§5-1-1이 항목 수 상한을 시간으로 바꾼 것과 같은 문제가 솔버 안에 남아 있다.

지금 고치지 않는 이유: 현재 계획은 하루 3개를 넘기는 일이 드물어 제약이 발동하지 않는다.
발동하지 않는 제약을 고치면 바뀐 게 맞는지 확인할 방법도 없다.

그 다음 순서:

```text
요일·프로젝트별 편차 반영     조건: 3개월 이상 누적. 표본이 적으면 노이즈다
가용시간 캘린더 연동          위의 "고정 일정 차감"이 그 일부를 이미 한다
계획 진행 중 축소 제안        재계획 기능이 있어야 성립한다
```


아래 둘은 "언젠가 하면 좋은 일"이 아니라 **조건이 충족되면 하는 일**이다. 조건 없이
적어두면 영원히 안 한다. 자료함 전환에서 폴더 구조를 조건부로 미룬 것과 같은 방식이다.

**`execution_records` ↔ `execution_items` 상태 정합 감사**

```text
조건   REOPENED로 설명되지 않는 불일치가 2건 이상 나오면
```

지금 알려진 불일치는 item 19 하나뿐이고, `REOPENED` 이벤트로 완전히 설명된다
(완료 → 3초 뒤 재열기, `reopen()`이 기록을 지우지 않는 설계). 즉 현재로선 버그가 아니라
정상 동작의 흔적이므로 감사 도구를 만들 이유가 없다. **§5-4의 status 우선 규칙이 이
불일치를 이미 안전하게 처리한다.**

두 건째가 나오는 순간 의미가 달라진다 — 설명되지 않는 불일치가 복수라면 완료 처리나
기록 생성 경로 어딘가에 실제 결함이 있다는 뜻이고, 그때는 규칙으로 덮지 말고 원인을
찾아야 한다. 판단 기준: 해당 `execution_item_id`의 `execution_item_events`에 `REOPENED`가
없는데 `status`와 최신 `outcome`이 어긋나 있는 경우.

**`legacy_execution_item_map` 정리**

```text
조건   execution_items가 200행을 넘거나, 2027-02-23(이관 후 6개월)이 지나면
```

지금은 19행이고 `execution_items`가 44행이라 이관 출처를 되짚는 일이 실제로 가능하다.
데이터가 충분히 쌓이거나 시간이 지나면 "2026-07 이전 schedule_block에서 온 조각"을
되짚을 일이 사라지고, 그때는 이력이 아니라 잔여물이 된다. 조건 충족 시 테이블을 지우되,
`docs/sql/backup/`에 덤프를 남기는 방식은 2026-08-23과 동일하게 한다.
