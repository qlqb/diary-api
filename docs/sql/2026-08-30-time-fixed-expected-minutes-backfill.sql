-- TIME_FIXED 실행 조각의 expected_minutes를 실제 구간으로 맞춘다.
--
-- 왜 어긋났나: AI 제안 검증(AiProposalService)이 expectedMinutes에 5~120 상한을 걸고
-- 있었는데, 그 상한은 "잘게 쪼갠 할 일"에 맞춘 값이라 알바·수업처럼 시각이 박힌 일정에는
-- 맞지 않았다. 모델은 검증을 통과하려고 시각은 진짜 값을 넣고 expectedMinutes만 120으로
-- 깎아 냈다. 그 결과 17:00~23:00짜리 알바가 120분으로 저장됐다.
--
-- 차단 자체는 정상이었다 — 가용시간 계산은 scheduled_start_at~scheduled_end_at을 쓰므로
-- 6시간이 통째로 막혔다. 어긋난 것은 화면 표시와 하루 부하 계산(limitDailyLoad)이다.
--
-- 코드는 같은 커밋에서 고쳤다: TIME_FIXED면 상한을 적용하지 않고 길이를 구간에서 계산한다.
-- 이 스크립트는 그 전에 이미 저장된 행만 되돌린다.
--
-- 적용 대상(2026-08-30 로컬 기준): execution_items 4건(알바 근무 월~목).
-- version을 올리는 이유: 이 값을 화면에 들고 있는 클라이언트가 있다면 그 사본은 낡은
-- 것이므로, 낙관적 잠금이 다음 수정에서 걸러 내게 한다.

-- 먼저 확인 (UPDATE 전에 무엇이 바뀔지 본다)
SELECT execution_item_id,
       title,
       expected_minutes                                                    AS before_minutes,
       TIMESTAMPDIFF(MINUTE, scheduled_start_at, scheduled_end_at)         AS after_minutes,
       scheduled_start_at,
       scheduled_end_at
FROM execution_items
WHERE placement_type = 'TIME_FIXED'
  AND expected_minutes <> TIMESTAMPDIFF(MINUTE, scheduled_start_at, scheduled_end_at)
ORDER BY execution_item_id;

-- 실제 수정
UPDATE execution_items
SET expected_minutes = TIMESTAMPDIFF(MINUTE, scheduled_start_at, scheduled_end_at),
    version          = version + 1,
    updated_at       = NOW()
WHERE placement_type = 'TIME_FIXED'
  AND expected_minutes <> TIMESTAMPDIFF(MINUTE, scheduled_start_at, scheduled_end_at);

-- 확인 (0건이어야 한다)
SELECT COUNT(*) AS remaining_mismatched
FROM execution_items
WHERE placement_type = 'TIME_FIXED'
  AND expected_minutes <> TIMESTAMPDIFF(MINUTE, scheduled_start_at, scheduled_end_at);
