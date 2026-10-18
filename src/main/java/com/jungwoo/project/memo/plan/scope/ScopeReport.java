package com.jungwoo.project.memo.plan.scope;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 요청 범위 목록과, 최종 초안에서 각 부분이 어떻게 처리됐는지에 대한 서버 판정(생성 건 단위). 요청 맥락(plan_request_json)에 남는다.
 *
 * @param status       EXTRACTED(범위를 뽑음) / NONE(범위를 말하지 않음) / FAILED(추출 호출 실패) / SKIPPED(재사용 등으로 호출 안 함)
 * @param requiredUnmet 검증된 필수 범위 중 충족되지 않은 부분이 있다(추가 과목 판단 설명 검사용)
 * @param remaining    검증된 범위 전체(선택 포함)의 미충족 부분 id(화면의 "남은 범위")
 * @param needsConfirm 원문에서 근거를 확인하지 못한 범위 id("확인 필요"). 충족 판정에서 뺀다
 * @param completion   ALL(검증 범위 전부 충족·확인 필요 없음) / REQUIRED_ONLY(필수만 충족) / NOT_COMPLETE / UNKNOWN(검증된 범위가 없음)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScopeReport(String status, List<RequestScope> scopes, List<PartState> parts, boolean requiredUnmet,
                          List<String> remaining, List<String> needsConfirm, String completion,
                          /** 범위 추출에서 상한·잘못된 원소로 버린 것이 있다 — 그때는 전체·필수 완료를 말하지 않는다 */
                          boolean incomplete) {

    public static final String EXTRACTED = "EXTRACTED";
    public static final String NONE = "NONE";
    public static final String FAILED = "FAILED";
    public static final String SKIPPED = "SKIPPED";

    public static ScopeReport empty(String status) {
        return new ScopeReport(status, List.of(), List.of(), false, List.of(), List.of(), Completion.UNKNOWN.name(), false);
    }

    /** 원문 확보 상태(최종 계획 입력 기준). */
    public enum Acquisition {
        /** 연습에 필요한 단위(구간)가 잘리지 않고 실렸다. */
        DELIVERED_UNIT,
        /** 일부만 실렸다(글자 상한으로 잘림). */
        EXCERPT_ONLY,
        /** 선택 목록에 보였지만 고르지 않았다. */
        SHOWN_NOT_SELECTED,
        /** 앱에 자료는 있지만 이번 입력에 실리지 않았다(골랐지만 예산·조회 실패로 빠짐, 목록 밖, 자료 연결 미확인). 사유는 acquisitionNote. */
        AVAILABLE_NOT_ACQUIRED,
        /** 앱에 이 부분의 자료가 없다. */
        NO_MATERIAL_IN_APP
    }

    /** 최종 초안에서의 반영 상태. */
    public enum Reflection {
        /** 이 부분을 다루는 항목이 있다(자료 원문 근거). */
        ITEM,
        /** 원문은 없지만 사용자가 가진 자료로 하는 연습 항목이 있다. */
        PRACTICE_WITH_USER_MATERIAL,
        /** 미뤘다(이유는 note). */
        DEFERRED,
        /** 이미 준비됐다(확인된 근거가 있을 때만 — 3단계). */
        ALREADY_PREPARED,
        /** 아무 처리도 없다. */
        UNHANDLED;

        public boolean satisfied() {
            return this == ITEM || this == PRACTICE_WITH_USER_MATERIAL || this == ALREADY_PREPARED;
        }
    }

    public enum Completion { ALL, REQUIRED_ONLY, NOT_COMPLETE, UNKNOWN }

    /**
     * @param items 이 부분에 연결된 최종 항목의 순번(0부터, 초안 항목 순서)
     * @param note  미룬 이유 등
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PartState(String partId, String scopeId, String acquisition, String reflection, List<Integer> items,
                            String note, String acquisitionNote) {
    }
}
