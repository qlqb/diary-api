package com.jungwoo.project.memo.material.dto;

/**
 * 분석 시작 요청의 내부 결과. 컨트롤러가 201과 200을 가르는 데만 쓰고 JSON으로 나가지 않는다.
 *
 * <p>응답 본문은 그대로 {@link MaterialAnalysisResponse}다 — 새로 만들었든 기존 DRAFT를
 * 돌려줬든 화면이 하는 일은 같기 때문에(검토 폼을 연다) 본문 모양을 가를 이유가 없다.
 * 다른 것은 "행이 하나 생겼는가"뿐이고 그건 상태 코드가 말한다.
 *
 * @param created 이 요청이 새 행을 만들었는가. 기존 열린 DRAFT를 그대로 돌려줬으면 false다.
 *                AI 호출이 실패해 FAILED 행을 남긴 경우도 행은 새로 생겼으므로 true다
 *                (기존 동작이 201이었고, 그 동작을 바꾸지 않는다).
 */
public record MaterialAnalysisStartResult(
        MaterialAnalysisResponse analysis,
        boolean created
) {

    public static MaterialAnalysisStartResult created(MaterialAnalysisResponse analysis) {
        return new MaterialAnalysisStartResult(analysis, true);
    }

    public static MaterialAnalysisStartResult reused(MaterialAnalysisResponse analysis) {
        return new MaterialAnalysisStartResult(analysis, false);
    }
}
