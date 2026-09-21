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
        String reason,
        /*
         * 이 변경을 가리키는 안정적인 이름. 서버가 정리안을 저장할 때 붙인다(모델이 주지 않는다).
         *
         * 왜 배열 순번이 아닌가: 사용자가 제목을 고치고 몇 개를 빼 둔 채 화면을 떠났다 돌아오는
         * 사이에 정리안이 새 판으로 바뀔 수 있다. 순번으로 저장해 두면 그때 편집이 엉뚱한 변경에
         * 붙는다. changeId는 "무엇을 어떻게 바꾸는가"에서 만들어지므로 같은 뜻의 변경이면 판이
         * 달라도 같은 값이고, 뜻이 다르면 달라진다.
         *
         * 자료별 변경안(레거시)에는 없다 — 그쪽은 순번으로 적용한다.
         */
        String changeId
) {
    /**
     * changeId 없이 만드는 편의 생성자. 모델 출력을 읽는 자리와 테스트가 쓴다 — changeId는
     * 프로젝트 정리안을 저장할 때 서버가 붙이는 값이라 그 전에는 없다.
     */
    public TopicChangeOp(String op, String tempId, Long topicId, Long parentTopicId, String parentTempId,
                         String title, String sourceType, String locator, List<Long> sectionIds, String role,
                         Long survivingTopicId, List<Long> absorbedTopicIds, List<TopicChangeOp> children,
                         String reason) {
        this(op, tempId, topicId, parentTopicId, parentTempId, title, sourceType, locator, sectionIds, role,
                survivingTopicId, absorbedTopicIds, children, reason, null);
    }

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
                sectionIds, role, survivingTopicId, absorbedTopicIds, children, reason, changeId);
    }

    public TopicChangeOp withChangeId(String id) {
        return new TopicChangeOp(op, tempId, topicId, parentTopicId, parentTempId, title, sourceType, locator,
                sectionIds, role, survivingTopicId, absorbedTopicIds, children, reason, id);
    }

    public TopicChangeOp withChildren(List<TopicChangeOp> newChildren) {
        return new TopicChangeOp(op, tempId, topicId, parentTopicId, parentTempId, title, sourceType, locator,
                sectionIds, role, survivingTopicId, absorbedTopicIds, newChildren, reason, changeId);
    }
}
