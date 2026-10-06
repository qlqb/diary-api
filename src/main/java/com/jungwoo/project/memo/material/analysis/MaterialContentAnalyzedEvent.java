package com.jungwoo.project.memo.material.analysis;

/** 자료 하나의 내용 분석(CONTENT)이 끝났다. 원문 단위가 생겼으니 교재 단서를 다시 볼 수 있다. */
public record MaterialContentAnalyzedEvent(Long userId, Long materialId) {
}
