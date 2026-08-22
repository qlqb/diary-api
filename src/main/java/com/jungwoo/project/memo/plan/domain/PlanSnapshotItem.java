package com.jungwoo.project.memo.plan.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.jungwoo.project.memo.execution.domain.PlacementType;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * plan_versions.items_snapshot에 저장되는 JSON 배열의 원소 하나.
 * "확정 시점에 이 조각을 이렇게 하기로 했다"를 그대로 얼려둔 값이다.
 *
 * executionItemId는 필수다 — 회고에서 스냅샷과 현재 상태를 대조하는 유일한 키이고,
 * 이 값이 없으면 "계획했는데 배치를 안 했다"와 "계획에 없었다"를 구분할 수 없다.
 * 조립 시점에 null이면 PlanSnapshotCodec이 거부한다.
 *
 * scheduledStartAt/scheduledEndAt까지 저장하는 이유는 "몇 시에 하기로 했었나"를 회고할 수
 * 있어야 하기 때문이다. 날짜만 남기면 "14시에 하려던 걸 22시에 했다"가 사라진다.
 *
 * courseTitle은 표시용 복사본이다. 프로젝트 이름이 나중에 바뀌어도 스냅샷은 확정 당시의
 * 이름을 유지한다 — 그게 역사 보존의 의미다.
 *
 * priority를 enum이 아니라 String으로 두는 것은 ProposalItemPayload와 같은 이유다.
 * 이 JSON은 스키마 없는 컬럼에 그대로 저장되고 몇 달 뒤에도 읽혀야 하므로, 값 집합이
 * 바뀌었을 때 과거 스냅샷이 통째로 읽히지 않는 상황을 만들지 않는다. 같은 이유로 모르는
 * 필드는 무시하고 읽는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanSnapshotItem(
        Long executionItemId,
        String title,
        Integer expectedMinutes,
        String priority,
        Long courseId,
        String courseTitle,
        Long topicId,
        PlacementType placementType,
        LocalDate scheduledDate,
        LocalDateTime scheduledStartAt,
        LocalDateTime scheduledEndAt,
        LocalDate planningStartDate,
        LocalDate planningEndDate,
        String reason
) {
}
