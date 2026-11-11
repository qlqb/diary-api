package com.jungwoo.project.memo.scheduling.domain;

/** 가용시간 후보 하나를 얼마나 신뢰할 수 있는지. AI_INFERRED 성격의 정보는 항상 LOW 이하로 취급한다. */
public enum AvailabilityConfidence {
    HIGH,
    MEDIUM,
    LOW
}
