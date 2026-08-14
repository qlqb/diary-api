package com.jungwoo.project.memo.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.jungwoo.project.memo.ai.domain.ProposalOperation;
import com.jungwoo.project.memo.execution.domain.PlacementType;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * ai_proposal_items.original_payload / edited_payload에 저장되는 JSON의 자바 표현.
 * targetDate는 서버가 요청의 targetDate로 확정해 넣는다 — 모델 출력에 맡기지 않는다.
 *
 * placementType이 DATE_ONLY면 scheduledStartAt/scheduledEndAt은 항상 null이고,
 * TIME_FIXED면 둘 다 not null이며 그 날짜가 targetDate와 같아야 한다 (execution_items의
 * 배치 무결성 조건과 동일). UNSCHEDULED면 scheduledStartAt/scheduledEndAt이 모두 null이고
 * targetDate는 아직 확정되지 않은 최초 추정값(대개 오늘)일 뿐이며, 7일 범위 배치 미리보기가
 * 실제 날짜를 결정한 뒤 적용 시점에 최종 날짜로 교체된다.
 *
 * 이 JSON은 스키마 없는 컬럼에 그대로 저장되므로 필드가 늘어날 수 있다 — 예전에 저장된 행이
 * 새 코드에서 읽히지 않는 상황(=적용 불가)을 막기 위해 모르는 필드는 무시하고 읽는다.
 *
 * earliestStartDate/deadlineDate는 UNSCHEDULED 후보를 Timefold가 배치할 때 참고하는
 * 선택적 힌트다. AI_INFERRED 값이며 사용자가 직접 확정한 사실이 아니므로 HARD 제약으로
 * 승격하지 않는다(마감일만 예외 — 사용자가 명시한 마감은 강한 제약으로 다룬다).
 *
 * operation부터 뒤쪽 필드는 기존 조각을 조정하는 제안(REDUCE/MOVE/DROP) 전용이다. 이 값들이
 * 없는(=이 기능 이전에 저장된) JSON은 Jackson이 전부 null로 읽고, operation이 null이면
 * CREATE로 취급하므로 기존 제안이 그대로 동작한다. before* 값은 화면에 "30분 -> 20분"처럼
 * 변경 전후를 보여주기 위해 제안 생성 시점의 대상 조각 상태를 복사해 둔 것이다(실제 적용
 * 안전성은 before 값이 아니라 base_version이 보장한다).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProposalItemPayload(
        String title,
        String description,
        Integer expectedMinutes,
        String priority,
        LocalDate targetDate,
        PlacementType placementType,
        LocalDateTime scheduledStartAt,
        LocalDateTime scheduledEndAt,
        LocalDate earliestStartDate,
        LocalDate deadlineDate,
        ProposalOperation operation,
        Long targetExecutionItemId,
        Long targetBaseVersion,
        String beforeTitle,
        Integer beforeExpectedMinutes,
        LocalDate beforeScheduledDate,
        String reason
) {

    /** 새 실행 조각을 만드는(기존 흐름 그대로인) 후보. */
    public static ProposalItemPayload create(
            String title, String description, Integer expectedMinutes, String priority, LocalDate targetDate,
            PlacementType placementType, LocalDateTime scheduledStartAt, LocalDateTime scheduledEndAt,
            LocalDate earliestStartDate, LocalDate deadlineDate
    ) {
        return new ProposalItemPayload(title, description, expectedMinutes, priority, targetDate,
                placementType, scheduledStartAt, scheduledEndAt, earliestStartDate, deadlineDate,
                ProposalOperation.CREATE, null, null, null, null, null, null);
    }

    /** 기존 조각을 조정하는 후보. */
    public static ProposalItemPayload adjust(
            ProposalOperation operation, Long targetExecutionItemId, Long targetBaseVersion,
            String title, Integer expectedMinutes, String priority, LocalDate targetDate,
            String beforeTitle, Integer beforeExpectedMinutes, LocalDate beforeScheduledDate, String reason
    ) {
        return new ProposalItemPayload(title, null, expectedMinutes, priority, targetDate,
                null, null, null, null, null,
                operation, targetExecutionItemId, targetBaseVersion,
                beforeTitle, beforeExpectedMinutes, beforeScheduledDate, reason);
    }

    /**
     * operation이 비어 있는 예전 payload는 CREATE로 읽는다.
     *
     * 아래 두 메서드에는 @JsonIgnore가 반드시 필요하다 — 특히 isAdjustment()는 Jackson이
     * boolean getter로 인식해 "adjustment" 필드를 JSON에 함께 써버리고, 그렇게 저장된 JSON을
     * 다시 읽을 때 알 수 없는 필드로 역직렬화가 실패한다(저장은 되는데 적용이 안 되는 상태).
     */
    @JsonIgnore
    public ProposalOperation effectiveOperation() {
        return operation != null ? operation : ProposalOperation.CREATE;
    }

    @JsonIgnore
    public boolean isAdjustment() {
        return effectiveOperation() != ProposalOperation.CREATE;
    }
}
