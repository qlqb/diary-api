package com.jungwoo.project.memo.plan.dto;

import com.jungwoo.project.memo.ai.dto.ProposalItem;
import com.jungwoo.project.memo.execution.domain.PlacementType;
import com.jungwoo.project.memo.plan.domain.ActionType;
import com.jungwoo.project.memo.plan.domain.DoneCriteriaSource;
import com.jungwoo.project.memo.plan.domain.EstimateConfidence;

import java.time.LocalDateTime;

/**
 * 실행 조각 하나. 서버가 검증·정규화를 마친 상태다.
 *
 * <p>조각의 조건은 하나다: <b>사용자가 앉아서 바로 시작할 수 있는가.</b> 그래서 무엇을
 * 볼지(anchor)와 무엇을 하면 끝인지(doneCriteria)가 둘 다 있어야 한다. "3장 복습"은 앉아서
 * 무엇을 할지 다시 정해야 하는 말이고, 그 순간 계획은 계획이 아니라 목록이 된다.
 *
 * @param topicId       어느 학습 항목인가. 확정 시 execution_items.topic_id로 남아 나중에
 *                      「이미 알아요」와 진행 상태가 이 조각을 되짚을 수 있게 한다
 * @param materialId    자료 anchor. 서버가 topic의 출처에서 채운다 — 모델이 고르지 않는다
 * @param sourceLocator 그 자료의 어디인가("2주차"). 역시 topic이 이미 아는 값이다
 * @param doneCriteria  무엇을 하면 끝인가. 비어 있으면 조각으로 인정하지 않는다
 * @param deadlineAt    근거 있는 마감. 없으면 null이고, 서버가 근거 없는 값을 지운다
 */
public record PlanItemDraft(
        Long topicId,
        Long courseId,
        String title,
        String description,
        String doneCriteria,
        /** 그 완료 기준을 누가 썼는가. 서버가 채웠으면 DEFAULT — 화면이 `기본` 라벨을 붙인다. */
        DoneCriteriaSource doneCriteriaSource,
        ActionType actionType,
        Integer expectedMinutes,
        String priority,
        Long materialId,
        String sourceLocator,
        LocalDateTime deadlineAt,
        EstimateConfidence estimateConfidence,
        String reason
) {

    /**
     * 자료 위치와 완료 기준을 본문에 합친다.
     *
     * <p>v1에는 이것들을 담을 컬럼이 없다. 컬럼을 만들지 않는 이유는 아직 이 값들로 조회하거나
     * 집계할 일이 없어서다 — 사용자가 읽을 문장으로만 쓰인다. 본문에 합쳐 두면 실행 항목과
     * 스냅샷에 자연히 함께 보존되고, 나중에 컬럼이 필요해지면 그때 옮기면 된다.
     */
    public String toDescription() {
        StringBuilder sb = new StringBuilder();
        if (description != null && !description.isBlank()) {
            sb.append(description.trim());
        }
        if (sourceLocator != null && !sourceLocator.isBlank()) {
            appendSeparator(sb);
            sb.append("자료: ").append(sourceLocator.trim());
        }
        appendSeparator(sb);
        sb.append("완료 기준: ").append(doneCriteria.trim());
        return sb.toString();
    }

    private static void appendSeparator(StringBuilder sb) {
        if (!sb.isEmpty()) {
            sb.append(" · ");
        }
    }

    /**
     * 제안 후보로 옮긴다.
     *
     * <p>항상 UNSCHEDULED다. 실제 시각은 Timefold가 정하고, 조각 생성은 시각을 정하지 않는다 —
     * 여기서 날짜를 박으면 배치가 할 일이 없어지고 마감 제약도 의미를 잃는다.
     */
    public ProposalItem toProposalItem() {
        return new ProposalItem(
                title, toDescription(), expectedMinutes, priority,
                PlacementType.UNSCHEDULED,
                null, null,
                null, null,
                null, null,
                courseId, deadlineAt, topicId,
                actionType, doneCriteria, doneCriteriaSource, sourceLocator);
    }
}
