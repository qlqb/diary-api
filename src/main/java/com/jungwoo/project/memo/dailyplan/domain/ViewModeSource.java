package com.jungwoo.project.memo.dailyplan.domain;

/**
 * viewMode가 어떻게 정해졌는지 추적. (MVP는 2값만 사용)
 * 확장 후보: SYSTEM_SUGGESTED, AI_RECOMMENDED (viewMode 가설 검증 후)
 */
public enum ViewModeSource {
    USER_DEFAULT,    // 기본 설정으로 생성됨
    USER_SELECTED    // 사용자가 오늘만 변경함
}
