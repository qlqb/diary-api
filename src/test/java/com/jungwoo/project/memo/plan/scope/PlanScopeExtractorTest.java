package com.jungwoo.project.memo.plan.scope;

import com.jungwoo.project.memo.ai.AiConsultationClient;
import com.jungwoo.project.memo.ai.AiUsageLimitService;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.plan.PlanMaterialContextService.CourseCatalog;
import com.jungwoo.project.memo.plan.selection.PromptTokenEstimator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PlanScopeExtractorTest {

    private final PlanScopeExtractor extractor = new PlanScopeExtractor(mock(AiConsultationClient.class),
            mock(AiUsageLimitService.class), mock(PromptTokenEstimator.class));

    private static CourseMaterial material(long id, String name) {
        CourseMaterial m = new CourseMaterial();
        m.setMaterialId(id);
        m.setOriginalFilename(name);
        return m;
    }

    private static CourseCatalog catalog(long courseId, String title, CourseMaterial... materials) {
        return new CourseCatalog(courseId, title, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(materials), 0);
    }

    private static final String SAID = "네트워크프로그래밍은 2주차, 6주차, 7주차 56번 장표까지\n스마트앱은 오픈북이라 pdf에 나오는거 보고 코드 채울 수 있으면 돼";

    private final List<CourseCatalog> catalogs = List.of(
            catalog(937L, "네트워크프로그래밍", material(459L, "07.소켓_시작하기_v8.pdf"), material(245L, "2.리눅스_개요_WSL2추가_v2.pdf"),
                    material(460L, "06.네트워크와_소켓_v5 (1).pdf")),
            catalog(939L, "스마트앱프로젝트", material(441L, "6.어댑터뷰.pdf")));

    @Test
    void 자료는_올린_순서로_보이고_핸들은_이번에_보여_준_같은_과목_자료만_인정한다() {
        PlanScopeExtractor.Registry registry = PlanScopeExtractor.registry(catalogs);
        String prompt = PlanScopeExtractor.prompt(new PlanScopeExtractor.Request(1L, "g", SAID, List.of(), catalogs), registry);
        // 올린 순서(id 순): 2.리눅스(245) → 07.소켓(459) → 06.네트워크(460). 번호로 다시 정렬하지 않는다.
        assertThat(prompt.indexOf("2.리눅스")).isLessThan(prompt.indexOf("07.소켓")).isLessThan(prompt.indexOf("06.네트워크"));

        String json = """
                {"scopes":[{"course":"c1","role":"OPTIONAL","goal":"필기시험","boundary":"2·6·7주차","quote":"7주차 56번 장표까지",
                  "parts":[{"label":"2주차","materials":["d1"]},{"label":"6주차","materials":["d3","d4"]},{"label":"7주차","materials":["d2"]}]},
                 {"course":"c2","role":"REQUIRED","goal":"PDF 보고 코드 빈칸","quote":"pdf에 나오는거 보고 코드 채울 수 있으면 돼",
                  "parts":[{"label":"SQLite","materials":[],"noMaterial":true},{"label":"RecyclerView","materials":["d4","d1"]}]},
                 {"course":"c9","role":"REQUIRED","quote":"지어낸 말","parts":[]}]}
                """;
        PlanScopeExtractor.Parsed parsed = extractor.parse(json, registry, SAID);
        assertThat(parsed.dropped()).isFalse();
        List<RequestScope> scopes = parsed.scopes();

        assertThat(scopes).hasSize(3);
        RequestScope net = scopes.get(0);
        assertThat(net.scopeId()).isEqualTo("S1");
        assertThat(net.courseId()).isEqualTo(937L);
        assertThat(net.verified()).isTrue();
        assertThat(net.basisSentence()).isEqualTo("네트워크프로그래밍은 2주차, 6주차, 7주차 56번 장표까지");
        assertThat(net.parts()).extracting(RequestScope.Part::partId).containsExactly("S1.P1", "S1.P2", "S1.P3");
        assertThat(net.parts().get(0).materialIds()).containsExactly(245L);
        assertThat(net.parts().get(1).materialIds()).containsExactly(460L); // d4는 다른 과목 자료라 버린다
        RequestScope app = scopes.get(1);
        assertThat(app.required()).isTrue();
        assertThat(app.parts().get(0).noMaterial()).isTrue();
        assertThat(app.parts().get(1).materialIds()).containsExactly(441L); // d1(네트워크 자료)은 버린다
        RequestScope fake = scopes.get(2);
        assertThat(fake.courseId()).isNull(); // 보여 주지 않은 과목 핸들
        assertThat(fake.verified()).isFalse(); // 원문에 없는 구절
        assertThat(fake.parts()).hasSize(1); // 부분이 없으면 범위 전체를 한 부분으로
    }

    @Test
    void 읽을_수_없는_응답은_null이고_범위가_없으면_빈_목록이다() {
        PlanScopeExtractor.Registry registry = PlanScopeExtractor.registry(catalogs);
        assertThat(extractor.parse("범위는 없어요", registry, SAID)).isNull();
        assertThat(extractor.parse("{\"scopes\":[]}", registry, SAID).scopes()).isEmpty();
    }
    @Test
    void 버린_범위가_있으면_불완전으로_표시하고_목록을_다_보여_주지_않은_과목의_자료_없음은_믿지_않는다() {
        PlanScopeExtractor.Registry registry = PlanScopeExtractor.registry(catalogs);
        StringBuilder many = new StringBuilder("{\"scopes\":[\"이상한 값\"");
        for (int i = 0; i < PlanScopeExtractor.MAX_SCOPES + 1; i++) {
            many.append(",{\"course\":\"c1\",\"quote\":\"2주차\",\"parts\":[{\"label\":\"p").append(i).append("\"}]}");
        }
        many.append("]}");
        PlanScopeExtractor.Parsed parsed = extractor.parse(many.toString(), registry, SAID);
        assertThat(parsed.dropped()).isTrue();
        assertThat(parsed.scopes()).hasSize(PlanScopeExtractor.MAX_SCOPES); // 잘못된 원소 뒤의 정상 범위는 살린다

        CourseMaterial[] lots = new CourseMaterial[PlanScopeExtractor.MAX_MATERIALS_PER_COURSE + 1];
        for (int i = 0; i < lots.length; i++) {
            lots[i] = material(1000L + i, (i + 1) + ".자료.pdf");
        }
        List<CourseCatalog> big = List.of(catalog(939L, "스마트앱프로젝트", lots));
        PlanScopeExtractor.Registry bigRegistry = PlanScopeExtractor.registry(big);
        assertThat(PlanScopeExtractor.prompt(new PlanScopeExtractor.Request(1L, "g", SAID, List.of(), big), bigRegistry))
                .contains("목록에서 생략");
        RequestScope.Part sqlite = extractor.parse("""
                {"scopes":[{"course":"c1","quote":"코드 채울 수 있으면 돼","parts":[{"label":"SQLite","materials":[],"noMaterial":true}]}]}
                """, bigRegistry, SAID).scopes().get(0).parts().get(0);
        assertThat(sqlite.noMaterial()).isFalse();
    }
    @Test
    void 응답은_구분자_형식을_요구하고_구분자를_빼먹어도_본문에서_JSON을_찾는다() {
        // 4단계 실호출에서 드러난 회귀: 형식 지시가 없어 구조화 JSON을 읽지 못했다(픽스처는 구분자를 대신 붙여 줘 놓쳤다).
        assertThat(PlanScopeExtractor.SYSTEM_PROMPT).contains(com.jungwoo.project.memo.ai.AiStreamParser.DELIMITER);
        PlanScopeExtractor.Registry registry = PlanScopeExtractor.registry(catalogs);
        assertThat(extractor.parse("범위를 뽑았어요.\n{\"scopes\":[]}", registry, SAID)).isNotNull();
    }

    private static final String TIME_SAID = "아래는 이 계획을 요청하기까지 사용자가 대화에서 말한 것이다(오래된 것부터).\n"
            + "- 오늘 자료구조 스택 괄호검사 과제 끝내고 빅데이터분석 좀 공부하고 싶은데 공부 계획 짜줘\n"
            + "- 오늘 오후4시까지 가능해\n- (기간 계획 초안 생성 요청)\n";

    @Test
    void 말한_시간은_원문_구절과_값이_맞을_때만_확인된_시간이_된다() {
        // 대화 2164 재현: "4시까지"가 자료를 읽은 상담 턴에서 AI 후보로 떨어져 예산에 안 들어갔다.
        PlanScopeExtractor.Registry registry = PlanScopeExtractor.registry(catalogs);
        ScopeReport.StudyTime ok = extractor.parse("""
                {"scopes":[],"studyTime":{"minutes":null,"per":"DAY","from":null,"until":"16:00","quote":"오늘 오후4시까지 가능해"}}
                """, registry, TIME_SAID).studyTime();
        assertThat(ok).isNotNull();
        assertThat(ok.until()).isEqualTo("16:00");
        assertThat(ok.from()).isNull();
        assertThat(ok.perDay()).isTrue();
        assertThat(ok.sentence()).isEqualTo("오늘 오후4시까지 가능해"); // 지시문의 발언 한 줄
        assertThat(ok.toBudget().until()).isEqualTo("16:00");

        // 값이 원문과 다르다(오후 4시를 04:00로) — 모델이 정한 값은 믿지 않는다
        assertThat(extractor.parse("""
                {"scopes":[],"studyTime":{"until":"04:00","quote":"오늘 오후4시까지 가능해"}}
                """, registry, TIME_SAID).studyTime()).isNull();
        // 원문에 없는 구절
        assertThat(extractor.parse("""
                {"scopes":[],"studyTime":{"until":"16:00","quote":"4시까지 공부한다"}}
                """, registry, TIME_SAID).studyTime()).isNull();
        // 원문에 없는 분량을 덧붙였다
        assertThat(extractor.parse("""
                {"scopes":[],"studyTime":{"minutes":120,"per":"DAY","until":"16:00","quote":"오늘 오후4시까지 가능해"}}
                """, registry, TIME_SAID).studyTime()).isNull();
        // 말하지 않았다
        assertThat(extractor.parse("{\"scopes\":[],\"studyTime\":null}", registry, TIME_SAID).studyTime()).isNull();
    }

    private ScopeReport.StudyTime time(String said, String studyTime, java.time.LocalTime reference) {
        return extractor.parse("{\"scopes\":[],\"studyTime\":" + studyTime + "}", PlanScopeExtractor.registry(catalogs),
                said, reference).studyTime();
    }

    @Test
    void 소수점_뒤_조각을_인용해도_발언_줄_전체와_대조해_통과하지_못한다() {
        assertThat(time("- 1.5시간만 오늘 공부할 수 있어\n",
                "{\"minutes\":300,\"per\":\"DAY\",\"quote\":\"5시간만 오늘 공부할 수 있어\"}", null)).isNull();
        assertThat(time("- 1.5시간만 오늘 공부할 수 있어\n",
                "{\"minutes\":90,\"per\":\"DAY\",\"quote\":\"1.5시간만 오늘 공부할 수 있어\"}", null).minutes()).isEqualTo(90);
    }

    @Test
    void 나중에_시간을_바꿔_말했으면_예전_구절은_확인되지_않는다() {
        String said = PlanScopeExtractor.CONVERSATION_HEADER
                + "\n- 오후4시까지 가능해\n- 아 오후6시까지 가능해\n- (기간 계획 초안 생성 요청)\n";
        assertThat(time(said, "{\"until\":\"16:00\",\"quote\":\"오후4시까지 가능해\"}", null)).isNull();
        assertThat(time(said, "{\"until\":\"18:00\",\"quote\":\"오후6시까지 가능해\"}", null).until()).isEqualTo("18:00");
    }

    @Test
    void 발언의_분량이나_시간대를_빠뜨리면_확인되지_않는다() {
        String said = "- 오늘 오후4시까지 한 시간만 공부할 수 있어\n";
        assertThat(time(said, "{\"until\":\"16:00\",\"quote\":\"오후4시까지 한 시간만\"}", null)).isNull();
        assertThat(time(said, "{\"minutes\":60,\"per\":\"DAY\",\"quote\":\"오후4시까지 한 시간만\"}", null)).isNull();
        ScopeReport.StudyTime both = time(said,
                "{\"minutes\":60,\"per\":\"DAY\",\"until\":\"16:00\",\"quote\":\"오후4시까지 한 시간만\"}", null);
        assertThat(both.minutes()).isEqualTo(60);
        assertThat(both.until()).isEqualTo("16:00");
    }

    @Test
    void 오전_오후를_말하지_않은_시각은_기준_시각_뒤의_가장_이른_시각만_인정한다() {
        String said = "- 4시까지 공부가능해 집중적으로 공부하고 싶어\n";
        java.time.LocalTime noon = java.time.LocalTime.of(12, 53);
        assertThat(time(said, "{\"until\":\"16:00\",\"quote\":\"4시까지 공부가능해\"}", noon).until()).isEqualTo("16:00");
        assertThat(time(said, "{\"until\":\"04:00\",\"quote\":\"4시까지 공부가능해\"}", noon)).isNull();
        // 기준 시각이 없으면 어느 쪽인지 정할 수 없다
        assertThat(time(said, "{\"until\":\"16:00\",\"quote\":\"4시까지 공부가능해\"}", null)).isNull();
        // 시작이 애매하면 기준 뒤의 가장 이른 시작, 끝은 그 뒤의 가장 이른 끝
        String evening = "- 8시부터 11시까지 할게\n";
        assertThat(time(evening, "{\"from\":\"20:00\",\"until\":\"23:00\",\"quote\":\"8시부터 11시까지\"}", noon))
                .isNotNull();
        assertThat(time(evening, "{\"from\":\"08:00\",\"until\":\"11:00\",\"quote\":\"8시부터 11시까지\"}", noon))
                .isNull();
        // 계획 첫날 아침부터 남는 시간이면 오전이다
        assertThat(time(evening, "{\"from\":\"08:00\",\"until\":\"11:00\",\"quote\":\"8시부터 11시까지\"}",
                java.time.LocalTime.of(7, 0))).isNotNull();
    }

    @Test
    void 여러_줄인_한_발언은_통째로_대조하고_구간이_되지_못한_시각이_있으면_확인되지_않는다() {
        String said = PlanScopeExtractor.CONVERSATION_HEADER
                + "따른다.\n- 오늘 한 시간만 공부할 수 있어\n오늘 오후4시까지 가능해\n- (기간 계획 초안 생성 요청)\n";
        assertThat(PlanScopeExtractor.utteranceLines(said))
                .containsExactly("오늘 한 시간만 공부할 수 있어\n오늘 오후4시까지 가능해", "(기간 계획 초안 생성 요청)");
        assertThat(time(said, "{\"until\":\"16:00\",\"quote\":\"오늘 오후4시까지 가능해\"}", null)).isNull();
        assertThat(time(said, "{\"minutes\":60,\"per\":\"DAY\",\"until\":\"16:00\",\"quote\":\"오늘 오후4시까지 가능해\"}",
                null)).isNotNull();
        // 화면에서 바로 쓴 지시문은 불릿이 있어도 전체가 한 발언(첫 줄을 머리말로 버리지 않는다)
        assertThat(PlanScopeExtractor.utteranceLines("오늘 한 시간만 공부할 수 있어\n- 오늘 오후4시까지 가능해"))
                .containsExactly("오늘 한 시간만 공부할 수 있어\n- 오늘 오후4시까지 가능해");
        assertThat(time("오늘 한 시간만 공부할 수 있어\n- 오늘 오후4시까지 가능해",
                "{\"until\":\"16:00\",\"quote\":\"오늘 오후4시까지 가능해\"}", null)).isNull();

        assertThat(time("- 오늘 한 시간만 공부하고 오후4시 전에 끝내야 해\n",
                "{\"minutes\":60,\"per\":\"DAY\",\"quote\":\"오늘 한 시간만 공부하고\"}", null)).isNull();
    }

    @Test
    void 대화_메시지_안의_불릿은_발언_경계가_아니다() {
        StringBuilder sb = new StringBuilder(PlanScopeExtractor.CONVERSATION_HEADER).append("따른다.\n");
        PlanScopeExtractor.appendUtterance(sb, "오늘 한 시간만 공부할 수 있어\n- 오늘 오후4시까지 가능해");
        PlanScopeExtractor.appendUtterance(sb, "(기간 계획 초안 생성 요청)");
        String said = sb.toString();

        assertThat(PlanScopeExtractor.utteranceLines(said))
                .containsExactly("오늘 한 시간만 공부할 수 있어\n- 오늘 오후4시까지 가능해", "(기간 계획 초안 생성 요청)");
        assertThat(time(said, "{\"until\":\"16:00\",\"quote\":\"오늘 오후4시까지 가능해\"}", null)).isNull();
        assertThat(time(said, "{\"minutes\":60,\"per\":\"DAY\",\"until\":\"16:00\",\"quote\":\"오늘 오후4시까지 가능해\"}",
                null)).isNotNull();
    }

    @Test
    void 시각_기준이_있으면_입력에_싣는다() {
        PlanScopeExtractor.Registry registry = PlanScopeExtractor.registry(catalogs);
        assertThat(PlanScopeExtractor.prompt(new PlanScopeExtractor.Request(1L, "g", SAID, List.of(), catalogs,
                java.time.LocalTime.of(12, 53)), registry)).contains("[시각 기준] 12:53");
    }
}
