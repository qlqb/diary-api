package com.jungwoo.project.memo.ai.consult;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 상담 턴 하나의 화면용 부가 정보. SSE {@code message.completed}와 대화 기록 조회가 같은 모양을 쓴다
 * (ai_messages.consult_json에 저장해 새로고침·재접속 뒤에도 같은 카드가 복구된다).
 *
 * @param evidence 이 답변을 위해 실제로 확인한 자료·앱 정보와 확인하지 못한 범위. 자료를 보지 않은 턴은 null
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConsultView(Question question, List<Understanding> understanding, Direction direction, Activity activity,
                          Evidence evidence) {

    public ConsultView(Question question, List<Understanding> understanding, Direction direction, Activity activity) {
        this(question, understanding, direction, activity, null);
    }

    public boolean isEmpty() {
        return question == null && direction == null && activity == null && evidence == null
                && (understanding == null || understanding.isEmpty());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Question(String id, String text, String why, String topic, List<Choice> choices, boolean multiSelect) {
    }

    /**
     * 선택지 하나. kind가 선택지의 의미를 정하고, 화면과 서버가 같은 뜻으로 다룬다.
     * <ul>
     *   <li>ANSWER — 질문에 대한 답 자체. 누르면 그 라벨이 내 답으로 보내진다.</li>
     *   <li>INPUT — 답이 아니라 "이런 걸 적어 주세요"라는 입력 안내. 누르면 아무것도 보내지 않고 입력창을 연다(서버도 이
     *       선택지로 온 답을 받지 않는다). "과목명과 날짜 말하기"를 답으로 보내 같은 질문이 되풀이되던 것을 막는다.</li>
     *   <li>LOOKUP — "내 자료에서 찾아봐". 보내면 서버가 자료 확인을 넓혀 한다.</li>
     * </ul>
     * 예전에 저장된 선택지(kind 없음)는 ANSWER다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(String id, String label, String kind) {
        public Choice(String id, String label) {
            this(id, label, null);
        }
    }

    /**
     * @param id           MEMORY면 user_contexts.context_id, BRIEF면 합의 항목 id(문자열)
     * @param source       MEMORY(장기적으로 기억) / BRIEF(이번 계획의 합의)
     * @param evidenceType STATED / SELF_REPORT / OBSERVED / INFERRED
     * @param scopeLabel   "자료구조 · 9/19~9/20"처럼 사람이 읽는 적용 범위. 범위를 모르면 null
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Understanding(String id, String source, String text, String evidenceType, String scopeLabel,
                                boolean isNew) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Direction(String before, String after, String reason, boolean affectsDraft) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Activity(String kind, Long courseId, String title, List<ActivityItem> items) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ActivityItem(String key, String label, Long topicId, Long sectionId) {
    }

    /**
     * 이번 답변의 근거 확인 결과. 서버가 실제로 모델에 실은 것만 담는다 — 목록에서 이름만 본 자료는 sources에 없다.
     *
     * @param summary  한 줄 요약("자료 5개 중 읽을 수 있는 4개를 검색 · 원문 3곳 확인 · 1곳은 읽을 수 없음")
     * @param sources  모델에 실은 근거. used=true가 답변에 쓰였다고 모델이 밝히고 서버가 확인한 것이다
     * @param gaps     확인하지 못한 범위와 그 이유(찾지 못함·읽을 수 없음·분석 중·한도)
     * @param rounds   자료 확인 횟수(서버 선행 조회 1 + 추가 읽기)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Evidence(String summary, List<Source> sources, List<Gap> gaps, int rounds) {
    }

    /**
     * @param ref        이 턴 안의 번호(E1·F1·S1)
     * @param kind       MATERIAL_TEXT(파일에서 읽은 원문) / MATERIAL_SECTION(자동 분석이 만든 구간 정보 — 원문 아님) /
     *                   APP_FACT(앱에 저장된 사실: 확정 과제 마감·등록된 수업·적용된 과목 정보)
     * @param title      파일 이름 또는 사실의 이름
     * @param origin     APP_FACT의 출처 종류(사용자 확정·원문 명시·추정·등록된 반복 일정 등). 그 외 null
     * @param locator    "p.3", "슬라이드 2", "구간 4"처럼 파일에서 확인한 위치. 모르면 null(만들지 않는다)
     * @param page       PDF 쪽으로 열 수 있을 때만 그 쪽 번호. PDF가 아니면 null
     * @param readState  FULL(그 단위 전체) / PARTIAL(걸린 줄 주변만) / EXCERPT_ONLY(분석 때 저장한 발췌만) / METADATA(원문 아님)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Source(String ref, String kind, String title, String courseTitle, String origin, Long materialId,
                         String locator, Integer page, String readState, boolean used, int round) {
    }

    /**
     * @param reason NOT_FOUND(검색 범위에서 못 찾음 — 정보가 없다는 뜻이 아니다) / NO_TEXT(텍스트 없음·스캔본) /
     *               EXTRACTION_FAILED / EXTRACTING(추출 대기) / NOT_READ_LIMIT(한도로 읽지 못함) / CHANGED(읽는 사이 바뀌거나 지워짐) /
     *               NO_MATERIAL(자료 없음)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Gap(String label, String reason, String detail) {
    }
}
