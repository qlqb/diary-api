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
}
