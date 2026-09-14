package com.jungwoo.project.memo.learning.structure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 자료 정리 변경안의 작업 하나. ops_json의 원소이자 모델 출력의 원소.
 *
 * <pre>
 * LINK    topicId, sectionIds, role, locator          기존 항목에 자료 구간 연결
 * ADD     tempId, parentTopicId|parentTempId, title,  새 항목(부모는 기존 id이거나 같은 변경안의 tempId)
 *         sourceType, locator, sectionIds, role
 * RENAME  topicId, title, reason                      이름 보완. id 유지
 * MOVE    topicId, parentTopicId(null=루트), reason    이동. id 유지
 * MERGE   survivingTopicId, absorbedTopicIds, reason  병합. 흡수된 항목은 ARCHIVED + merged_into
 * SPLIT   topicId, children[ADD 모양], reason          분할. 원본은 부모로 남고 기록도 남는다
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TopicChangeOp(
        String op,
        String tempId,
        Long topicId,
        Long parentTopicId,
        String parentTempId,
        String title,
        String sourceType,
        String locator,
        List<Long> sectionIds,
        String role,
        Long survivingTopicId,
        List<Long> absorbedTopicIds,
        List<TopicChangeOp> children,
        String reason
) {
    public static final String LINK = "LINK";
    public static final String ADD = "ADD";
    public static final String RENAME = "RENAME";
    public static final String MOVE = "MOVE";
    public static final String MERGE = "MERGE";
    public static final String SPLIT = "SPLIT";

    /** 학습 범위·기록에 영향을 주는 큰 변경인가. 요약에서 접지 않고 보여준다. */
    public boolean isStructural() {
        return MOVE.equals(op) || MERGE.equals(op) || SPLIT.equals(op);
    }

    public TopicChangeOp withTitle(String newTitle) {
        return new TopicChangeOp(op, tempId, topicId, parentTopicId, parentTempId, newTitle, sourceType, locator,
                sectionIds, role, survivingTopicId, absorbedTopicIds, children, reason);
    }
}
