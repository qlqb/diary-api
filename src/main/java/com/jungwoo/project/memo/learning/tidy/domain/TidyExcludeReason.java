package com.jungwoo.project.memo.learning.tidy.domain;

/** 이 자료를 이번 정리에서 왜 뺐는가. 숨기지 않고 화면에 그대로 보여준다. */
public enum TidyExcludeReason {
    /** 아직 내용 분석 중이거나 차례를 기다린다. */
    ANALYZING,
    /** 분석이 실패했다. */
    FAILED,
    /** 본문을 읽지 못했다. */
    NO_TEXT,
    /** 입력 한도에 걸려 이번에는 싣지 못했다. 부분 정리라는 것을 화면이 말한다. */
    OVER_BUDGET,
    /** 요청 시점에는 연결돼 있었는데 그 사이 연결이 끊겼다. */
    UNLINKED,
    DELETED;

    public String label() {
        return switch (this) {
            case ANALYZING -> "분석 중";
            case FAILED -> "분석 실패";
            case NO_TEXT -> "본문 없음";
            case OVER_BUDGET -> "이번에 못 실음";
            case UNLINKED -> "연결 해제됨";
            case DELETED -> "삭제됨";
        };
    }
}
