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
 *
 * (2026-09-29) 실제 수업·계획 범위 정정 — 트리 구조가 아니라 "이 과목을 실제로 어떻게 다뤘나"를 바꾼다.
 * CLASS          topicId, week?, afterTopicId?           실제 수업 주차·순서. 교재 위치(트리)는 그대로다
 * MATERIAL_WEEK  materialId, week                        자료의 실제 수업 주차(사용자 확정으로 저장)
 * SCOPE_EXCLUDE  topicId, label                          이 시험·계획 범위에서 제외(학습 완료로 보지 않는다)
 *
 * MOVE의 afterTopicId: 새 부모 아래 이 형제 뒤에 둔다. 0이면 맨 앞, null이면 맨 뒤(예전 동작).
 * LINK의 topicId가 null이고 tempId가 있으면 같은 정리안이 새로 만드는 항목(ADD의 tempId)에 잇는다.
 * ADD의 materialId: 구간 없이 만드는 항목의 출처 자료(예: 교재 목차로 만든 골격).
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
        String changeId,
        Long afterTopicId,
        Integer week,
        Long materialId,
        String label,
        /*
         * 누가 낸 변경인가. null = AI 정리, USER = 직접 조작, REQUEST = 사용자의 자연어 요청을 해석한 것.
         * AI가 정리안을 새로 만들어도 사용자가 낸 변경은 새 판으로 옮겨 간다(버리지 않는다).
         * TOC = 서버가 교재 목차에서 그대로 옮긴 골격.
         */
        String by,
        /*
         * (2026-10-04) 이 ADD가 교재 목차의 몇 번째 항목(1부터)에서 왔나. 모델이 낼 수 있지만 서버가 그 항목이 있는지 확인하고
         * 제목·쪽을 목차에서 다시 채운다. 목차에서 온 변경은 적용할 때 목차 근거가 그대로인지 다시 본다.
         */
        Integer tocLine
) {
    /** 예전 모양(by까지). tocLine은 비운다. */
    public TopicChangeOp(String op, String tempId, Long topicId, Long parentTopicId, String parentTempId,
                         String title, String sourceType, String locator, List<Long> sectionIds, String role,
                         Long survivingTopicId, List<Long> absorbedTopicIds, List<TopicChangeOp> children,
                         String reason, String changeId, Long afterTopicId, Integer week, Long materialId,
                         String label, String by) {
        this(op, tempId, topicId, parentTopicId, parentTempId, title, sourceType, locator, sectionIds, role,
                survivingTopicId, absorbedTopicIds, children, reason, changeId, afterTopicId, week, materialId, label,
                by, null);
    }

    /** 예전 모양(changeId까지). 새 칸은 비운다. */
    public TopicChangeOp(String op, String tempId, Long topicId, Long parentTopicId, String parentTempId,
                         String title, String sourceType, String locator, List<Long> sectionIds, String role,
                         Long survivingTopicId, List<Long> absorbedTopicIds, List<TopicChangeOp> children,
                         String reason, String changeId) {
        this(op, tempId, topicId, parentTopicId, parentTempId, title, sourceType, locator, sectionIds, role,
                survivingTopicId, absorbedTopicIds, children, reason, changeId, null, null, null, null, null);
    }

    /**
     * changeId 없이 만드는 편의 생성자. 모델 출력을 읽는 자리와 테스트가 쓴다 — changeId는
     * 프로젝트 정리안을 저장할 때 서버가 붙이는 값이라 그 전에는 없다.
     */
    public TopicChangeOp(String op, String tempId, Long topicId, Long parentTopicId, String parentTempId,
                         String title, String sourceType, String locator, List<Long> sectionIds, String role,
                         Long survivingTopicId, List<Long> absorbedTopicIds, List<TopicChangeOp> children,
                         String reason) {
        this(op, tempId, topicId, parentTopicId, parentTempId, title, sourceType, locator, sectionIds, role,
                survivingTopicId, absorbedTopicIds, children, reason, null, null, null, null, null, null);
    }

    public static final String LINK = "LINK";
    public static final String ADD = "ADD";
    public static final String RENAME = "RENAME";
    public static final String MOVE = "MOVE";
    public static final String MERGE = "MERGE";
    public static final String SPLIT = "SPLIT";
    public static final String CLASS = "CLASS";
    public static final String MATERIAL_WEEK = "MATERIAL_WEEK";
    public static final String SCOPE_EXCLUDE = "SCOPE_EXCLUDE";

    /** 학습 구조(트리)를 바꾸는 작업인가. 아니면 실제 수업·계획 범위 정정이다(트리 판을 올리지 않는다). */
    public boolean isTreeOp() {
        return !CLASS.equals(op) && !MATERIAL_WEEK.equals(op) && !SCOPE_EXCLUDE.equals(op);
    }

    /** 학습 범위·기록에 영향을 주는 큰 변경인가. 요약에서 접지 않고 보여준다. */
    public boolean isStructural() {
        return MOVE.equals(op) || MERGE.equals(op) || SPLIT.equals(op);
    }

    public TopicChangeOp withTitle(String newTitle) {
        return new TopicChangeOp(op, tempId, topicId, parentTopicId, parentTempId, newTitle, sourceType, locator,
                sectionIds, role, survivingTopicId, absorbedTopicIds, children, reason, changeId,
                afterTopicId, week, materialId, label, by, tocLine);
    }

    public TopicChangeOp withChangeId(String id) {
        return new TopicChangeOp(op, tempId, topicId, parentTopicId, parentTempId, title, sourceType, locator,
                sectionIds, role, survivingTopicId, absorbedTopicIds, children, reason, id,
                afterTopicId, week, materialId, label, by, tocLine);
    }

    public TopicChangeOp withChildren(List<TopicChangeOp> newChildren) {
        return new TopicChangeOp(op, tempId, topicId, parentTopicId, parentTempId, title, sourceType, locator,
                sectionIds, role, survivingTopicId, absorbedTopicIds, newChildren, reason, changeId,
                afterTopicId, week, materialId, label, by, tocLine);
    }

    public TopicChangeOp withBy(String who) {
        return new TopicChangeOp(op, tempId, topicId, parentTopicId, parentTempId, title, sourceType, locator,
                sectionIds, role, survivingTopicId, absorbedTopicIds, children, reason, changeId,
                afterTopicId, week, materialId, label, who, tocLine);
    }

    /** 교재 목차 항목에서 온 ADD로 표시하고 제목·출처를 목차 값으로 바꾼다. */
    public TopicChangeOp fromToc(int line, String tocTitle, String tocLocator) {
        return new TopicChangeOp(op, tempId, topicId, parentTopicId, parentTempId, tocTitle, "SOURCE", tocLocator,
                sectionIds, role, survivingTopicId, absorbedTopicIds, children, reason, changeId,
                afterTopicId, week, materialId, label, by, line);
    }

    /** 목차(골격 또는 목차 항목)에서 온 변경인가. 적용할 때 목차 근거를 다시 본다. */
    public boolean isFromToc() {
        return "TOC".equals(by) || tocLine != null;
    }

    /** changeId를 지운다(판을 다시 세울 때). */
    public TopicChangeOp withoutChangeId() {
        return withChangeId(null);
    }
}
