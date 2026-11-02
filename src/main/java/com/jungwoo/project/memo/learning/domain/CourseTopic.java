package com.jungwoo.project.memo.learning.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 확정된(Apply된) 학습 구조 트리의 노드. course_topics 테이블과 1:1 대응.
 *
 * Material Agent의 apply()를 통해서만 생성된다 — 직접 CRUD 엔드포인트는 없다
 * (구조 자체를 사용자가 임의로 만드는 것이 아니라 항상 분석 결과의 확정으로 생긴다).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CourseTopic {

    private Long topicId;

    private Long userId;

    private Long courseId;

    private Long parentTopicId;

    private String title;

    private Integer orderIndex;

    private TopicSourceType sourceType;

    private Long sourceMaterialId;

    private String sourceLocator;

    /** (2026-10-04) 웹 목차에서 온 항목이면 그 웹 리비전. 업로드 자료 출처(sourceMaterialId)와 섞지 않는다. */
    private Long sourceWebRevisionId;

    /** 목차에서 온 항목이 어느 책의 것인가(BookKey). 교재가 바뀐 뒤 이전 교재 항목을 새 교재 범위로 세지 않기 위해. */
    private String sourceTextbookKey;

    /**
     * 같은 교재 목차 안의 원본 순번(1부터). 트리 순서(orderIndex)와 달리 바뀌지 않는다 — 제목이 같은 단원을 구분한다.
     * (2026-10-06) 화면 표시용("목차 N번째")이다. 목차 항목과 짝을 찾을 때는 tocKey*를 쓴다(규칙이 바뀌면 순번은 달라진다).
     */
    private Integer sourceTocSeq;

    /**
     * (2026-10-06) 목차 항목 열쇠. 만들 때 한 번 정하고 바꾸지 않는다.
     * kind WEB = hash는 목차 원문 해시, line은 원문 줄 번호 · MATERIAL = hash는 파일 해시, line은 추출 순번.
     */
    private String tocKeyKind;
    private String tocKeyHash;
    private Integer tocKeyLine;
    /** SET(열쇠 있음) · MISSING_SOURCE(목차에서 왔지만 열쇠를 확정 못 함) · null(목차 토픽이 아님). */
    private String tocKeyState;

    private TopicStatus status;

    /**
     * 사용자가 이 항목에 대해 직접 말한 사실. 없으면 null이고 그것이 기본이다.
     * "모른다"와 "모른다고 답했다"를 구분하지 않는다 — 둘 다 근거 없음이고 판단이 같다.
     */
    private TopicUserMark userMark;

    /** 병합으로 ARCHIVED 됐으면 살아남은 항목. 그 외 null. */
    private Long mergedIntoTopicId;

    /** 병합·분할에서 학습 기록 승계가 애매할 때 남기는 안내. 사용자가 확인하면 지운다. */
    private String reviewNote;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
