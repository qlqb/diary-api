-- 이미 있는 핵심 표의 구조 스냅샷 (2026-11-02)
--
-- ★ 적용용 아님. 이 파일은 이미 운영 DB에 존재하는 표를 기록한 스냅샷이다. 적용하라고 만든 마이그레이션이 아니다.
--   새 환경을 만들 때만 쓴다. 기존 DB에 실행하지 않는다.
--
-- 뜬 날: 2026-11-02 · 출처 DB: 로컬 `memo` (MariaDB 10.4.32), `SHOW CREATE TABLE`.
-- 대상: docs/context/SCHEMA.md에서 `※ DDL 없음`이 붙은 8개 표 — 이 표들은 저장소에 CREATE TABLE이 없었다.
-- 바꾼 것: `CREATE TABLE IF NOT EXISTS`(재실행 가능), 표마다 다른 `AUTO_INCREMENT=n` 카운터 제거. 그 밖에는 DB가 돌려준 그대로다.
-- 사용자 데이터는 넣지 않았다(구조만).
-- 순서: 다른 docs/sql 파일보다 먼저 적용한다(대부분의 표가 users·execution_items 등을 참조한다).

CREATE TABLE IF NOT EXISTS `users` (
  `user_id` bigint(20) NOT NULL AUTO_INCREMENT,
  `email` varchar(255) NOT NULL,
  `password_hash` varchar(255) NOT NULL,
  `nickname` varchar(50) NOT NULL,
  `role` varchar(20) NOT NULL,
  `status` varchar(20) NOT NULL,
  `last_login_at` timestamp NULL DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `updated_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`user_id`),
  UNIQUE KEY `email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
;

CREATE TABLE IF NOT EXISTS `diaries` (
  `diary_id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL,
  `written_date` date NOT NULL,
  `title` varchar(100) NOT NULL,
  `content` text NOT NULL,
  `mood` varchar(20) DEFAULT NULL,
  `visibility` varchar(20) NOT NULL,
  `weather` varchar(20) DEFAULT NULL,
  `is_favorite` tinyint(1) NOT NULL DEFAULT 0,
  `is_deleted` tinyint(1) NOT NULL DEFAULT 0,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `updated_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`diary_id`),
  KEY `fk_diaries_user` (`user_id`),
  CONSTRAINT `fk_diaries_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
;

CREATE TABLE IF NOT EXISTS `diary_revisions` (
  `revision_id` bigint(20) NOT NULL AUTO_INCREMENT,
  `diary_id` bigint(20) NOT NULL,
  `written_date` date DEFAULT NULL,
  `title` varchar(100) NOT NULL,
  `content` text NOT NULL,
  `mood` varchar(20) DEFAULT NULL,
  `visibility` varchar(20) DEFAULT NULL,
  `weather` varchar(20) DEFAULT NULL,
  `is_favorite` tinyint(1) NOT NULL DEFAULT 0,
  `edited_at` timestamp NOT NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`revision_id`),
  KEY `fk_revisions_diary` (`diary_id`),
  CONSTRAINT `fk_revisions_diary` FOREIGN KEY (`diary_id`) REFERENCES `diaries` (`diary_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
;

CREATE TABLE IF NOT EXISTS `execution_items` (
  `execution_item_id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL,
  `topic_id` bigint(20) DEFAULT NULL,
  `course_id` bigint(20) DEFAULT NULL,
  `plan_version_id` bigint(20) DEFAULT NULL,
  `source_execution_item_id` bigint(20) DEFAULT NULL,
  `title` varchar(255) NOT NULL,
  `description` text DEFAULT NULL,
  `placement_type` varchar(20) NOT NULL DEFAULT 'UNSCHEDULED',
  `scheduled_date` date DEFAULT NULL,
  `planning_start_date` date DEFAULT NULL,
  `planning_end_date` date DEFAULT NULL,
  `scheduled_start_at` datetime DEFAULT NULL,
  `scheduled_end_at` datetime DEFAULT NULL,
  `deadline_at` datetime DEFAULT NULL COMMENT '이 시각까지 끝나야 계획상 의미가 있음. 스케줄러 HARD 제약',
  `expected_minutes` int(11) DEFAULT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'PLANNED',
  `priority` varchar(20) NOT NULL DEFAULT 'SHOULD',
  `order_index` int(11) NOT NULL DEFAULT 0,
  `routine_id` bigint(20) DEFAULT NULL,
  `origin_type` varchar(30) NOT NULL DEFAULT 'MANUAL',
  `modified_after_creation` tinyint(1) NOT NULL DEFAULT 0,
  `version` bigint(20) NOT NULL DEFAULT 0,
  `is_deleted` tinyint(1) NOT NULL DEFAULT 0,
  `created_at` datetime NOT NULL DEFAULT current_timestamp(),
  `updated_at` datetime NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`execution_item_id`),
  UNIQUE KEY `uq_execution_items_id_user` (`execution_item_id`,`user_id`),
  KEY `idx_execution_items_user_deleted_date` (`user_id`,`is_deleted`,`scheduled_date`),
  KEY `idx_execution_items_user_deleted_status` (`user_id`,`is_deleted`,`status`),
  KEY `idx_execution_items_user_period` (`user_id`,`scheduled_start_at`,`scheduled_end_at`),
  KEY `idx_execution_items_source_user` (`source_execution_item_id`,`user_id`),
  KEY `idx_execution_items_routine` (`routine_id`),
  KEY `idx_execution_items_user_course` (`user_id`,`course_id`),
  KEY `idx_execution_items_plan_version` (`user_id`,`plan_version_id`),
  KEY `idx_execution_items_planning_range` (`user_id`,`planning_start_date`,`planning_end_date`),
  CONSTRAINT `fk_execution_items_source_user` FOREIGN KEY (`source_execution_item_id`, `user_id`) REFERENCES `execution_items` (`execution_item_id`, `user_id`),
  CONSTRAINT `fk_execution_items_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`),
  CONSTRAINT `chk_execution_items_placement` CHECK (`placement_type` = 'UNSCHEDULED' and `scheduled_date` is null and `scheduled_start_at` is null and `scheduled_end_at` is null or `placement_type` = 'DATE_ONLY' and `scheduled_date` is not null and `scheduled_start_at` is null and `scheduled_end_at` is null or `placement_type` = 'TIME_FIXED' and `scheduled_date` is not null and `scheduled_start_at` is not null and `scheduled_end_at` is not null and cast(`scheduled_start_at` as date) = `scheduled_date` and `scheduled_start_at` < `scheduled_end_at`),
  CONSTRAINT `chk_execution_items_expected_minutes` CHECK (`expected_minutes` is null or `expected_minutes` > 0),
  CONSTRAINT `chk_execution_items_status` CHECK (`status` in ('PLANNED','PARTIAL','HOLD','DONE','CANCELLED')),
  CONSTRAINT `chk_execution_items_priority` CHECK (`priority` in ('MUST','SHOULD','OPTIONAL')),
  CONSTRAINT `chk_execution_items_flags` CHECK (`modified_after_creation` in (0,1) and `is_deleted` in (0,1)),
  CONSTRAINT `chk_execution_items_version` CHECK (`version` >= 0),
  CONSTRAINT `chk_execution_items_planning_range` CHECK ((`placement_type` = 'UNSCHEDULED' or `planning_start_date` is null and `planning_end_date` is null) and (`planning_start_date` is null and `planning_end_date` is null or `planning_start_date` is not null and `planning_end_date` is not null and `planning_start_date` <= `planning_end_date`))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
;

CREATE TABLE IF NOT EXISTS `execution_records` (
  `execution_record_id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL,
  `execution_item_id` bigint(20) NOT NULL,
  `outcome` varchar(20) NOT NULL,
  `started_at` datetime DEFAULT NULL,
  `ended_at` datetime DEFAULT NULL,
  `actual_minutes` int(11) DEFAULT NULL,
  `completion_percent` tinyint(3) unsigned NOT NULL,
  `note` text DEFAULT NULL,
  `remaining_execution_item_id` bigint(20) DEFAULT NULL,
  `recorded_at` datetime NOT NULL DEFAULT current_timestamp(),
  `created_at` datetime NOT NULL DEFAULT current_timestamp(),
  `blocker_kind` varchar(12) DEFAULT NULL,
  `support_level` varchar(10) DEFAULT NULL,
  `stuck_step` varchar(300) DEFAULT NULL,
  PRIMARY KEY (`execution_record_id`),
  KEY `idx_execution_records_item_user` (`execution_item_id`,`user_id`),
  KEY `idx_execution_records_user_recorded` (`user_id`,`recorded_at`),
  KEY `idx_execution_records_remaining_user` (`remaining_execution_item_id`,`user_id`),
  CONSTRAINT `fk_execution_records_item_user` FOREIGN KEY (`execution_item_id`, `user_id`) REFERENCES `execution_items` (`execution_item_id`, `user_id`),
  CONSTRAINT `fk_execution_records_remaining_user` FOREIGN KEY (`remaining_execution_item_id`, `user_id`) REFERENCES `execution_items` (`execution_item_id`, `user_id`),
  CONSTRAINT `chk_execution_records_outcome` CHECK (`outcome` in ('COMPLETED','PARTIAL','NOT_DONE')),
  CONSTRAINT `chk_execution_records_time` CHECK (`started_at` is null or `ended_at` is null or `started_at` <= `ended_at`),
  CONSTRAINT `chk_execution_records_actual_minutes` CHECK (`actual_minutes` is null or `actual_minutes` >= 0),
  CONSTRAINT `chk_execution_records_completion` CHECK (`outcome` = 'COMPLETED' and `completion_percent` = 100 or `outcome` = 'PARTIAL' and `completion_percent` between 1 and 99 or `outcome` = 'NOT_DONE' and `completion_percent` = 0),
  CONSTRAINT `chk_execution_records_remaining` CHECK (`outcome` = 'PARTIAL' and `remaining_execution_item_id` is not null or `outcome` <> 'PARTIAL' and `remaining_execution_item_id` is null),
  CONSTRAINT `chk_execution_records_blocker_kind` CHECK (`blocker_kind` is null or `blocker_kind` in ('TIME','CONCEPT','ENERGY','OTHER')),
  CONSTRAINT `chk_execution_records_support_level` CHECK (`support_level` is null or `support_level` in ('SOLO','GUIDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
;

CREATE TABLE IF NOT EXISTS `execution_item_events` (
  `execution_item_event_id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL,
  `execution_item_id` bigint(20) NOT NULL,
  `related_execution_item_id` bigint(20) DEFAULT NULL,
  `event_type` varchar(30) NOT NULL,
  `reason` text DEFAULT NULL,
  `before_state` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL CHECK (json_valid(`before_state`)),
  `after_state` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL CHECK (json_valid(`after_state`)),
  `before_version` bigint(20) DEFAULT NULL,
  `after_version` bigint(20) DEFAULT NULL,
  `actor_type` varchar(20) NOT NULL DEFAULT 'USER',
  `occurred_at` datetime NOT NULL DEFAULT current_timestamp(),
  `created_at` datetime NOT NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`execution_item_event_id`),
  KEY `idx_execution_events_item_user` (`execution_item_id`,`user_id`),
  KEY `idx_execution_events_related_user` (`related_execution_item_id`,`user_id`),
  KEY `idx_execution_events_user_type_occurred` (`user_id`,`event_type`,`occurred_at`),
  CONSTRAINT `fk_execution_events_item_user` FOREIGN KEY (`execution_item_id`, `user_id`) REFERENCES `execution_items` (`execution_item_id`, `user_id`),
  CONSTRAINT `fk_execution_events_related_user` FOREIGN KEY (`related_execution_item_id`, `user_id`) REFERENCES `execution_items` (`execution_item_id`, `user_id`),
  CONSTRAINT `chk_execution_events_actor` CHECK (`actor_type` in ('USER','AI','SYSTEM','MIGRATION','UNKNOWN')),
  CONSTRAINT `chk_execution_events_version` CHECK ((`before_version` is null or `before_version` >= 0) and (`after_version` is null or `after_version` >= 0) and (`before_version` is null or `after_version` is null or `before_version` <= `after_version`)),
  CONSTRAINT `chk_execution_events_type` CHECK (`event_type` in ('CREATED','MOVED','REDUCED','SPLIT','HOLD','RESUMED','REOPENED','CANCELLED','PRIORITY_CHANGED','DELETED','RESTORED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
;

CREATE TABLE IF NOT EXISTS `ai_proposals` (
  `proposal_id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL,
  `conversation_id` bigint(20) DEFAULT NULL,
  `source_message_id` bigint(20) DEFAULT NULL,
  `target_scope` varchar(30) NOT NULL,
  `unavailable_windows` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL CHECK (json_valid(`unavailable_windows`)),
  `status` varchar(30) NOT NULL DEFAULT 'PROPOSED',
  `created_at` datetime NOT NULL DEFAULT current_timestamp(),
  `expires_at` datetime DEFAULT NULL,
  `responded_at` datetime DEFAULT NULL,
  `plan_start_date` date DEFAULT NULL,
  `plan_end_date` date DEFAULT NULL,
  `plan_intensity` varchar(20) DEFAULT NULL,
  `plan_target_minutes` int(11) DEFAULT NULL,
  `plan_strategy_json` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '초안 시점의 판단. 확정 시 plan_versions.strategy_json으로 복사된다' CHECK (json_valid(`plan_strategy_json`)),
  `plan_provenance_json` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '생성 회차에 모델에 제공한 정보의 스냅샷. 서버 소유·불변' CHECK (json_valid(`plan_provenance_json`)),
  `plan_request_json` longtext DEFAULT NULL,
  `review_state_json` longtext DEFAULT NULL,
  PRIMARY KEY (`proposal_id`),
  UNIQUE KEY `uq_ai_proposals_id_user` (`proposal_id`,`user_id`),
  UNIQUE KEY `uq_ai_proposals_source_message` (`source_message_id`),
  KEY `idx_ai_proposals_user_status_created` (`user_id`,`status`,`created_at`),
  CONSTRAINT `fk_ai_proposals_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`),
  CONSTRAINT `chk_ai_proposals_scope` CHECK (`target_scope` in ('PLAN','TODAY','EXECUTION','CONTEXT','MIXED')),
  CONSTRAINT `chk_ai_proposals_status` CHECK (`status` in ('PROPOSED','APPLIED','MODIFIED_APPLIED','DISMISSED','EXPIRED')),
  CONSTRAINT `chk_ai_proposals_expiry` CHECK (`expires_at` is null or `created_at` <= `expires_at`),
  CONSTRAINT `chk_ai_proposals_plan_period` CHECK (`plan_start_date` is null and `plan_end_date` is null or `plan_start_date` is not null and `plan_end_date` is not null and `plan_start_date` <= `plan_end_date`),
  CONSTRAINT `chk_ai_proposals_plan_intensity` CHECK (`plan_intensity` is null or `plan_intensity` in ('LIGHT','NORMAL','FOCUSED')),
  CONSTRAINT `chk_ai_proposals_plan_target_minutes` CHECK (`plan_target_minutes` is null or `plan_target_minutes` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
;

CREATE TABLE IF NOT EXISTS `ai_proposal_items` (
  `proposal_item_id` bigint(20) NOT NULL AUTO_INCREMENT,
  `proposal_id` bigint(20) NOT NULL,
  `user_id` bigint(20) NOT NULL,
  `item_type` varchar(30) NOT NULL,
  `original_payload` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL CHECK (json_valid(`original_payload`)),
  `evidence_json` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '이 항목의 근거(회차 refId·서버 계산·AI 추정)와 그 상태. 서버 소유' CHECK (json_valid(`evidence_json`)),
  `edited_payload` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL CHECK (json_valid(`edited_payload`)),
  `target_item_id` bigint(20) DEFAULT NULL,
  `base_version` bigint(20) DEFAULT NULL,
  `status` varchar(30) NOT NULL DEFAULT 'PROPOSED',
  `created_item_type` varchar(30) DEFAULT NULL,
  `created_item_id` bigint(20) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT current_timestamp(),
  `responded_at` datetime DEFAULT NULL,
  PRIMARY KEY (`proposal_item_id`),
  KEY `idx_ai_proposal_items_proposal_user` (`proposal_id`,`user_id`),
  KEY `idx_ai_proposal_items_target` (`item_type`,`target_item_id`),
  CONSTRAINT `fk_ai_proposal_items_proposal_user` FOREIGN KEY (`proposal_id`, `user_id`) REFERENCES `ai_proposals` (`proposal_id`, `user_id`) ON DELETE CASCADE,
  CONSTRAINT `chk_ai_proposal_items_type` CHECK (`item_type` in ('PLAN_ITEM','EXECUTION_ITEM','CONTEXT_ITEM')),
  CONSTRAINT `chk_ai_proposal_items_status` CHECK (`status` in ('PROPOSED','APPLIED','MODIFIED_APPLIED','DISMISSED','EXPIRED')),
  CONSTRAINT `chk_ai_proposal_items_base_version` CHECK (`base_version` is null or `base_version` >= 0),
  CONSTRAINT `chk_ai_proposal_items_created_target` CHECK (`created_item_type` is null and `created_item_id` is null or `created_item_type` in ('PLAN_ITEM','EXECUTION_ITEM','CONTEXT_ITEM') and `created_item_id` is not null)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
;
