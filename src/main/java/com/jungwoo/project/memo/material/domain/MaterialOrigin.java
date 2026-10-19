package com.jungwoo.project.memo.material.domain;

/** course_materials.origin — 자료가 어디서 들어왔나. NULL = 자료함·과목·ZIP으로 올린 예전 방식. */
public enum MaterialOrigin {
    /**
     * 상담에서 올린 교재 사진. 서버가 글자를 읽어 본문·구간·단원 추정 연결을 직접 만든다(자동 분석 대상 아님).
     * 원본은 올린 지 30일 뒤 지운다 — 읽은 본문은 자료를 지울 때까지 남는다.
     */
    CONSULT_PHOTO
}
