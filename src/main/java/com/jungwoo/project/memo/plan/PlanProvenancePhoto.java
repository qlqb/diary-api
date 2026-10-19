package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.learning.domain.TopicLinkOrigin;
import com.jungwoo.project.memo.learning.domain.TopicMaterialLink;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 상담 사진 구간의 단원 연결 상태 문자열("PHOTO_GUESS:12", "USER:12", "NONE"). 계획 생성 때 근거 기록에 남기고, 초안 최신성
 * 판단 때 지금 값과 비교한다 — 같은 함수로 만들어야 비교가 맞다.
 */
public final class PlanProvenancePhoto {

    private PlanProvenancePhoto() {
    }

    public static String state(List<TopicMaterialLink> activeLinksOfMaterial, Long sectionId) {
        String out = activeLinksOfMaterial == null ? "" : activeLinksOfMaterial.stream()
                .filter(l -> Objects.equals(l.getSectionId(), sectionId))
                .sorted(java.util.Comparator.comparing(TopicMaterialLink::getTopicId))
                .map(l -> (l.getOrigin() == TopicLinkOrigin.PHOTO_GUESS ? "PHOTO_GUESS" : "USER") + ":" + l.getTopicId())
                .collect(Collectors.joining(","));
        return out.isEmpty() ? "NONE" : out;
    }
}
