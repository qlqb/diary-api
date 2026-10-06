-- =====================================================================
-- 2026-10-06 이벤트 기반 학습 진도 1단계 E — 과거 기록 백필의 진행 위치
-- =====================================================================
--
-- 앞선 마이그레이션: docs/sql/2026-10-06-class-sessions.sql. 이 파일은 그 뒤에 적용한다.
-- 계획: docs/handoff/learning-events-stage1-plan-2026-10-06.md §6·§11.7.
--
-- 새 표만 만든다. 백필은 설정(learning.events.backfill.enabled=true)을 켠 서버가 한 번 돌린다 — 운영 DB에서는 사용자가 백업 뒤
-- 새 서버(1단계 B 이후)로 다시 띄운 것을 확인하고 켠다.
-- 다시 돌려도 된다(IF NOT EXISTS, MariaDB 10.4).
--
-- 적용 후 확인:
--   SELECT * FROM learning_event_backfill;   -- 백필을 돌린 뒤 원본 종류마다 done=1, report에 처리·이벤트·미대응·건너뜀 수
--
-- 되돌리기: DROP TABLE IF EXISTS learning_event_backfill;   (이미 만든 이벤트는 그대로다)
-- =====================================================================

--   source       EXECUTION_RECORD · USER_CONTEXT · CORRECTION · TOPIC · MATERIAL_LINK
--   last_id      이 원본 종류에서 마지막으로 끝낸 id(과목 단위 종류는 course_id). 한 건 처리와 같은 트랜잭션에서 올린다
--   lease_owner  지금 돌리는 서버(무작위 id). lease_until이 지나면 다른 서버가 이어받는다
--   report       {"processed":n,"written":n,"skipped":n,"unmapped":n} — 개인 텍스트 없음
CREATE TABLE IF NOT EXISTS learning_event_backfill (
    source      VARCHAR(30)  NOT NULL,
    last_id     BIGINT       NOT NULL DEFAULT 0,
    done        TINYINT(1)   NOT NULL DEFAULT 0,
    report      LONGTEXT     NULL,
    lease_owner VARCHAR(64)  NULL,
    lease_until DATETIME     NULL,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (source)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
