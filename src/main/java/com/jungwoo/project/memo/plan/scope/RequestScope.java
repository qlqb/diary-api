package com.jungwoo.project.memo.plan.scope;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 이번 계획 요청에서 사용자가 말한 범위 하나(생성 건 단위). 자료 선택보다 먼저 사용자 원문에서 뽑아 서버가 확정한다 — 선택·계획
 * 결과가 이 범위를 빠뜨렸는지 대조하는 기준이다(설계: docs/handoff/plan-intent-preservation-plan-2026-10-18.md §2).
 *
 * @param scopeId       서버가 매긴 id(S1, S2…). 이 생성 건 안에서만 유일하다
 * @param courseId      프로젝트. 원문에서 과목을 정할 수 없으면 null — 그 범위는 임의 과목에 연결해 "반영"이 되지 않는다
 * @param role          REQUIRED("끝내고 싶어") / OPTIONAL("가능하면")
 * @param goal          무엇을 할 수 있게 되는가(예 "배포 PDF만 보고 코드 빈칸 채우기")
 * @param boundary      시작·끝·제외를 원문 그대로 요약한 것(예 "붓꽃·타이타닉까지")
 * @param quote         근거가 된 사용자 원문 구절(모델이 낸 것)
 * @param basisSentence 서버가 원문에서 찾은 그 구절의 문장. quote를 확인하지 못했으면 null
 * @param verified      quote가 이번 요청의 사용자 원문에 그대로 있다. false면 "확인 필요" 후보 — 필수 예산을 받지 않고 충족 근거가 되지 않는다
 * @param parts         독립적으로 누락을 판정할 수 있는 단위들
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RequestScope(String scopeId, Long courseId, String courseTitle, String role, String goal, String boundary,
                           String quote, String basisSentence, boolean verified, List<Part> parts) {

    public static final String REQUIRED = "REQUIRED";
    public static final String OPTIONAL = "OPTIONAL";

    public boolean required() {
        return REQUIRED.equals(role);
    }

    /**
     * 범위의 한 부분(예 "2주차", "SQLite", "교재에 표시한 시험 범위").
     *
     * @param partId       서버가 매긴 id(S1.P1…)
     * @param materialIds  이 부분을 다루는 앱 자료(같은 과목·같은 사용자인 것만 남긴다). 비어 있을 수 있다
     * @param noMaterial   앱에 이 부분의 자료가 없다고 범위 추출이 판단했다(예 SQLite 자료 미업로드)
     * @param userMaterial 사용자가 따로 가진 자료로 한다고 말했다(예 "책에 표시해 놨어", "배포 PDF") — 앱에 없어도 연습은 계획할 수 있다
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Part(String partId, String label, List<Long> materialIds, boolean noMaterial, boolean userMaterial) {
    }
}
