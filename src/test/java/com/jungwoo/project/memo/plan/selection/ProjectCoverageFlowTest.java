package com.jungwoo.project.memo.plan.selection;

import com.jungwoo.project.memo.plan.PeriodPlanDraftGenerator.Generated;
import com.jungwoo.project.memo.plan.domain.ProjectOutcome;
import com.jungwoo.project.memo.plan.domain.ProjectOutcome.Disposition;
import com.jungwoo.project.memo.plan.domain.ProjectOutcome.MaterialState;
import com.jungwoo.project.memo.plan.provenance.PlanItemEvidence;
import com.jungwoo.project.memo.plan.trace.PlanGenerationTraceService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.jungwoo.project.memo.plan.selection.PlanSelectionFixture.refOf;
import static com.jungwoo.project.memo.plan.selection.PlanSelectionFixture.selection;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 2026-09-19 handoff §5·§6·§14.3의 결정적 회귀(T01·T02·T03·T05·T06).
 *
 * <p>재현하는 실패: 후보가 선택 호출의 입력 예산을 넘으면 펼친 묶음을 <b>앞에서부터</b> 채워, 목록 뒤쪽 프로젝트는 후보가
 * 한 줄도 모델에 전달되지 않았다. 그런데 초안은 그 프로젝트를 "미뤘다"고만 했다 — 보지 못한 것과 뺀 것이 구분되지 않았다.
 */
class ProjectCoverageFlowTest {

    private static final int PROJECTS = 8;
    private static final int SECTIONS_PER_PROJECT = 40;

    private static String roles(String... roles) {
        return "[" + String.join(",", java.util.Arrays.stream(roles).map(r -> "\"" + r + "\"").toList()) + "]";
    }

    /** 프로젝트 8개 × 구간 40개(토픽 없음 — 구조 제안 승인 전의 신규 사용자). order는 프로젝트를 만드는 순서다. */
    private static PlanSelectionFixture manyProjects(List<Integer> order) {
        PlanSelectionFixture f = new PlanSelectionFixture();
        for (int p : order) {
            f.course(p, "프로젝트" + p);
            f.material(p * 10L, "자료" + p + ".pdf", (long) p);
            for (int s = 1; s <= SECTIONS_PER_PROJECT; s++) {
                f.section(p * 1000L + s, p * 10L, "P" + p + " 구간 " + s + " 개념과 예제를 설명하는 긴 제목",
                        roles("CONCEPT", "EXAMPLE"), s, "원문-P" + p + "-" + s);
            }
        }
        ReflectionTestUtils.setField(f.selector, "inputTokenBudget", 5000);
        return f;
    }

    private static List<String> groupHandles(String prompt) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?m)^\\s+(g\\d+)\\s").matcher(prompt);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** 펼친 목록에 줄로 실린 프로젝트 번호. */
    private static Set<Integer> projectsListed(String expandedPrompt) {
        Set<Integer> out = new HashSet<>();
        Matcher m = Pattern.compile("m\\d+ \\[[^\\]]*\\] P(\\d+) 구간").matcher(expandedPrompt);
        while (m.find()) {
            out.add(Integer.parseInt(m.group(1)));
        }
        return out;
    }

    private Generated runAllExpanded(PlanSelectionFixture f) {
        AtomicInteger round = new AtomicInteger();
        f.selectionAnswer = prompt -> round.getAndIncrement() == 0
                ? selection(List.of(), List.of(), groupHandles(prompt))
                : selection(List.of(PlanSelectionFixture.handleOf(prompt, "P1 구간 1 ")), List.of(), List.of());
        f.planAnswer = prompt -> PlanSelectionFixture.planWithOneItem(refOf(prompt, "P1 구간 1 "));
        return f.generate("전체를 처음부터 훑어보고 싶어");
    }

    @Test
    void T01_후보가_한도를_넘어도_입력_순서와_무관하게_모든_프로젝트의_후보가_모델에_전달된다() {
        List<Integer> forward = new ArrayList<>();
        for (int i = 1; i <= PROJECTS; i++) {
            forward.add(i);
        }
        List<Integer> reverse = new ArrayList<>(forward);
        Collections.reverse(reverse);
        List<Integer> shuffled = new ArrayList<>(forward);
        Collections.shuffle(shuffled, new Random(20260919));

        for (List<Integer> order : List.of(forward, reverse, shuffled)) {
            PlanSelectionFixture f = manyProjects(order);
            Generated generated = runAllExpanded(f);

            assertThat(f.selectionPrompts()).hasSize(2);
            String expanded = f.selectionPrompts().get(1);
            // 예산이 모자라 전부는 못 싣는다 — 그래도 어느 프로젝트도 0줄이 아니다.
            assertThat(projectsListed(expanded)).as("순서 %s", order).hasSize(PROJECTS);
            assertThat(expanded).contains("이번에 보여 주지 못했다");

            List<ProjectOutcome> outcomes = generated.strategy().projects();
            assertThat(outcomes).extracting(ProjectOutcome::courseId)
                    .containsExactlyInAnyOrderElementsOf(order.stream().map(Integer::longValue).toList());
            assertThat(outcomes).allSatisfy(o -> {
                assertThat(o.candidates()).isEqualTo(SECTIONS_PER_PROJECT);
                assertThat(o.shown()).as("프로젝트 %s에 줄로 전달된 후보", o.courseId()).isPositive();
            });
            // 어느 프로젝트도 "목록에도 못 실림"으로 끝나지 않는다.
            assertThat(outcomes).noneMatch(o -> o.materialState() == MaterialState.NOT_LISTED);
        }
    }

    @Test
    void T01_펼치기_요청이_일부뿐이면_요청하지_않은_프로젝트는_개요만_본_것으로_기록되고_제외로_포장되지_않는다() {
        List<Integer> order = List.of(1, 2, 3);
        PlanSelectionFixture f = manyProjects(order);
        AtomicInteger round = new AtomicInteger();
        f.selectionAnswer = prompt -> round.getAndIncrement() == 0
                ? selection(List.of(), List.of(), groupHandles(prompt).subList(0, 1))
                : selection(List.of(PlanSelectionFixture.handleOf(prompt, "P1 구간 1 ")), List.of(), List.of());
        // 모델이 2번을 "제외"라고 하고 3번은 아예 말하지 않는다.
        f.planAnswer = prompt -> "{\"title\":\"t\",\"strategy\":{\"goal\":\"g\",\"projects\":["
                + "{\"courseId\":1,\"disposition\":\"INCLUDED\",\"reason\":\"도입부부터\"},"
                + "{\"courseId\":2,\"disposition\":\"EXCLUDED\",\"reason\":\"중요도가 낮아 다음으로\"},"
                + "{\"courseId\":999,\"disposition\":\"INCLUDED\",\"reason\":\"범위 밖\"}]},"
                + "\"items\":[{\"title\":\"P1 도입\",\"description\":\"읽는다 · 완료: 끝\",\"expectedMinutes\":20,"
                + "\"priority\":\"SHOULD\",\"courseId\":1,\"reason\":\"r\",\"refIds\":[\"" + refOf(prompt, "P1 구간 1 ") + "\"]}]}";

        Generated generated = f.generate("전체 훑기");
        List<ProjectOutcome> outcomes = generated.strategy().projects();

        assertThat(outcomes).hasSize(3); // 범위 밖 999는 버린다.
        ProjectOutcome one = outcomes.stream().filter(o -> o.courseId() == 1).findFirst().orElseThrow();
        ProjectOutcome two = outcomes.stream().filter(o -> o.courseId() == 2).findFirst().orElseThrow();
        ProjectOutcome three = outcomes.stream().filter(o -> o.courseId() == 3).findFirst().orElseThrow();
        assertThat(one.disposition()).isEqualTo(Disposition.INCLUDED);
        assertThat(one.materialState()).isEqualTo(MaterialState.TEXT_DELIVERED);
        assertThat(one.delivered()).isEqualTo(1);
        // 2번: 묶음 요약(개요)은 봤고 모델이 펼치지 않기로 했다 — 개요 수준의 판단으로 남고, 자료 상태는 "개요만"이다.
        assertThat(two.shown()).isZero();
        assertThat(two.materialState()).isEqualTo(MaterialState.OUTLINE_ONLY);
        assertThat(two.disposition()).isEqualTo(Disposition.EXCLUDED_BY_CHOICE);
        assertThat(two.decidedBy()).isEqualTo("MODEL");
        // T02: 3번은 모델이 결과를 내지 않았다 — 서버가 미검토로 명시하고, 항목이나 제외 이유를 지어내지 않는다.
        assertThat(three.disposition()).isEqualTo(Disposition.NOT_REVIEWED);
        assertThat(three.decidedBy()).isEqualTo("SERVER");
        assertThat(three.itemCount()).isZero();
        assertThat(generated.items()).hasSize(1);
    }

    @Test
    void T03_자료_없음_분석_대기_개요만_원문_전달은_서로_다른_상태다() {
        PlanSelectionFixture f = new PlanSelectionFixture();
        f.course(1, "자료 없음");
        f.course(2, "원문 전달");
        f.material(20, "a.pdf", 2L);
        f.section(2001, 20, "재귀 개념", roles("CONCEPT"), 1, "원문-재귀");
        f.course(3, "개요만");
        f.material(30, "b.pdf", 3L);
        f.section(3001, 30, "정렬 개념", roles("CONCEPT"), 1, "원문-정렬");
        f.selectionAnswer = prompt -> selection(List.of(PlanSelectionFixture.handleOf(prompt, "재귀 개념")), List.of(), List.of());
        f.planAnswer = prompt -> "{\"title\":\"t\",\"strategy\":{\"goal\":\"g\",\"projects\":["
                + "{\"courseId\":1,\"disposition\":\"UNDECIDED\",\"reason\":\"자료가 없어 정하지 못함\"},"
                + "{\"courseId\":2,\"disposition\":\"INCLUDED\",\"reason\":\"재귀부터\"},"
                + "{\"courseId\":3,\"disposition\":\"EXCLUDED\",\"reason\":\"사용자가 이번엔 재귀만 보겠다고 함\"}]},"
                + "\"items\":[{\"title\":\"재귀\",\"description\":\"푼다 · 완료: 끝\",\"expectedMinutes\":30,"
                + "\"priority\":\"SHOULD\",\"courseId\":2,\"reason\":\"r\",\"refIds\":[\"" + refOf(prompt, "재귀 개념") + "\"]}]}";

        List<ProjectOutcome> outcomes = f.generate("재귀만 볼래").strategy().projects();

        assertThat(outcomes).extracting(ProjectOutcome::materialState)
                .containsExactly(MaterialState.NO_MATERIAL, MaterialState.TEXT_DELIVERED, MaterialState.OUTLINE_ONLY);
        assertThat(outcomes.get(0).nextAction()).isEqualTo(ProjectOutcome.NextAction.UPLOAD_MATERIAL);
        // 목록을 본 뒤의 의도적 제외는 모델의 판단 그대로 남는다.
        assertThat(outcomes.get(2).disposition()).isEqualTo(Disposition.EXCLUDED_BY_CHOICE);
        assertThat(outcomes.get(2).decidedBy()).isEqualTo("MODEL");
        assertThat(outcomes.get(2).reason()).contains("재귀만");
    }

    @Test
    void T05_운영_안내만_근거로_든_항목은_요청_없이는_학습_항목이_되지_않고_혼합_구간의_학습_근거는_남는다() {
        PlanSelectionFixture f = new PlanSelectionFixture();
        f.course(1, "자료구조");
        f.material(10, "강의계획서.pdf", 1L);
        f.section(101, 10, "평가 비율과 출결", roles("ADMIN"), 1, "중간 30 기말 40 과제 30");
        f.section(102, 10, "1주차 개요와 수업 규칙", roles("CONCEPT", "ADMIN"), 2, "자료구조란 무엇인가, 지각 3회는 결석");
        f.selectionAnswer = prompt -> selection(List.of(PlanSelectionFixture.handleOf(prompt, "평가 비율과 출결"),
                PlanSelectionFixture.handleOf(prompt, "1주차 개요와 수업 규칙")), List.of(), List.of());
        f.planAnswer = prompt -> {
            String admin = refOf(prompt, "평가 비율과 출결");
            String mixed = refOf(prompt, "1주차 개요와 수업 규칙");
            return "{\"title\":\"t\",\"items\":["
                    + item("평가 비율 5줄 정리", admin, "AI_PRACTICE") + ","
                    + item("자료구조 정의를 한 문장으로 설명", mixed, "AI_PRACTICE") + ","
                    + item("출결 규칙 확인(요청)", admin, "USER_REQUEST") + "]}";
        };

        Generated generated = f.generate(null);

        assertThat(generated.items()).extracting(i -> i.title())
                .containsExactly("자료구조 정의를 한 문장으로 설명", "출결 규칙 확인(요청)");
        assertThat(generated.strategy().deferred()).anySatisfy(d -> {
            assertThat(d.title()).isEqualTo("평가 비율 5줄 정리");
            assertThat(d.reason()).contains("운영 안내");
        });
        // 운영 안내는 여전히 계획 입력(판단 배경)에 있다 — 문서 전체를 차단하지 않는다.
        assertThat(f.planPrompt()).contains("중간 30 기말 40");
        assertThat(generated.itemEvidence()).extracting(PlanItemEvidence::origin).containsExactly("AI_PRACTICE", "USER_REQUEST");
    }

    @Test
    void T06_원문_과제라는_출처는_전달된_원문을_인용했을_때만_남는다() {
        PlanSelectionFixture f = new PlanSelectionFixture();
        f.course(1, "자료구조");
        f.material(10, "a.pdf", 1L);
        f.section(101, 10, "실습 1", roles("EXERCISE"), 1, "실습-원문");
        f.selectionAnswer = prompt -> selection(List.of(PlanSelectionFixture.handleOf(prompt, "실습 1")), List.of(), List.of());
        f.planAnswer = prompt -> "{\"title\":\"t\",\"items\":["
                + item("실습 1 수행", refOf(prompt, "실습 1"), "SOURCE_TASK") + ","
                + item("근거 없는 원문 과제", "s999", "SOURCE_TASK") + "]}";

        Generated generated = f.generate(null);

        assertThat(generated.itemEvidence()).extracting(PlanItemEvidence::origin).containsExactly("SOURCE_TASK", "AI_PRACTICE");
        assertThat(generated.itemEvidence().get(1).unknownRefCount()).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void T06_목록_전달과_원문_전달이_호출별로_기록된다() {
        PlanSelectionFixture f = new PlanSelectionFixture();
        PlanGenerationTraceService traces = mock(PlanGenerationTraceService.class);
        f.generator.setTraceService(traces);
        f.course(1, "자료구조");
        f.material(10, "a.pdf", 1L);
        f.section(101, 10, "개념 A", roles("CONCEPT"), 1, "원문-A");
        f.section(102, 10, "개념 B", roles("CONCEPT"), 2, "원문-B");
        f.selectionAnswer = prompt -> selection(List.of(PlanSelectionFixture.handleOf(prompt, "개념 A")), List.of(), List.of());
        f.planAnswer = prompt -> PlanSelectionFixture.planWithOneItem(refOf(prompt, "개념 A"));

        f.generate(null);

        ArgumentCaptor<List<PlanGenerationTraceService.Entry>> captor = ArgumentCaptor.forClass(List.class);
        verify(traces).save(anyLong(), anyString(), captor.capture());
        List<PlanGenerationTraceService.Entry> entries = captor.getValue();
        assertThat(entries).extracting(PlanGenerationTraceService.Entry::callKind).containsExactly("SELECTION", "PLAN");
        // 선택 호출: 두 구간이 목록으로 전달됐다. 계획 호출: 고른 한 구간만 원문으로 전달됐다.
        assertThat(entries.get(0).sectionIds()).containsExactlyInAnyOrder(101L, 102L);
        assertThat(entries.get(0).deliveredSectionIds()).isEmpty();
        assertThat(entries.get(1).deliveredSectionIds()).containsExactly(101L);
        assertThat(entries.get(1).userPrompt()).contains("원문-A").doesNotContain("원문-B");
        assertThat(entries.get(1).materialIds()).contains(10L);
    }

    private static String item(String title, String ref, String origin) {
        return "{\"title\":\"" + title + "\",\"description\":\"한다 · 완료: 끝\",\"expectedMinutes\":20,"
                + "\"priority\":\"SHOULD\",\"courseId\":1,\"reason\":\"r\",\"refIds\":[\"" + ref + "\"],\"origin\":\"" + origin + "\"}";
    }
}
