package com.jungwoo.project.memo.learning.correction;

import lombok.Data;

/** 학습 항목 하나에 걸린 기록 수. 구조 변경의 영향을 미리 보일 때 쓴다(바꾸지 않는다). */
@Data
public class TopicRecordCount {
    private Long topicId;
    /** 아직 할 실행 항목(계획·오늘에 남은 것). */
    private int openItems;
    /** 끝낸 실행 항목. */
    private int doneItems;
    /** 그 항목에 걸린 자기평가·기억. */
    private int contexts;
    /** 진도 상태(NOT_STARTED/IN_PROGRESS/…) 또는 null. */
    private String progress;
}
