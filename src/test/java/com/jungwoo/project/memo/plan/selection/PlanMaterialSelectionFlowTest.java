package com.jungwoo.project.memo.plan.selection;

import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.ServiceUnavailableException;
import com.jungwoo.project.memo.learning.domain.TopicProgressStatus;
import com.jungwoo.project.memo.learning.domain.TopicUserMark;
import com.jungwoo.project.memo.material.domain.MaterialStatus;
import com.jungwoo.project.memo.plan.PeriodPlanDraftGenerator.Generated;
import com.jungwoo.project.memo.plan.provenance.PlanItemEvidence;
import com.jungwoo.project.memo.plan.provenance.ProvenanceSourceType;
import com.jungwoo.project.memo.plan.provenance.ProvidedSource;
import com.jungwoo.project.memo.plan.provenance.ServerCalculation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.jungwoo.project.memo.plan.selection.PlanSelectionFixture.handleOf;
import static com.jungwoo.project.memo.plan.selection.PlanSelectionFixture.refOf;
import static com.jungwoo.project.memo.plan.selection.PlanSelectionFixture.selection;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AI 자료 선택 → 서버 조회 → 계획 호출(15번 문서 §7). 선택 모델의 출력을 제어하고 <b>최종 계획 호출에 들어간 문자열</b>까지 본다.
 *
 * <p>여기서 증명하는 것: 모델이 고른 구간의 저장된 원문이 계획 호출에 실린다. 서버의 역할 순위·토픽당 2개·미연결 8개
 * 같은 절단이 그 앞에서 후보를 없애지 않는다. 판단 사실은 선택과 무관하게 남는다. 돌아온 id는 검증되고, 실패와
 * 빈 선택은 구분된다.
 */
class PlanMaterialSelectionFlowTest {

    private PlanSelectionFixture f;

    @BeforeEach
    void setUp() {
        f = new PlanSelectionFixture();
        f.course(1, "자료구조");
    }

    private static String rolesJson(String... roles) {
        return "[\"" + String.join("\",\"", roles) + "\"]";
    }

    @Test
    void T1_토픽_120개의_뒤쪽_구간을_모델이_고르면_그_원문이_계획_호출에_들어간다() {
        f.material(10, "semester.pdf", 1L);
        for (long i = 1; i <= 120; i++) {
            f.topic(1, i, null, "주제 " + i, i + "주차", TopicProgressStatus.NOT_STARTED);
            f.section(1000 + i, 10, "주제 " + i + " 실습", rolesJson("EXERCISE"), (int) i, "원문-" + i + " 고유문장");
            f.link(1, i, 1000 + i);
        }
        f.selectionAnswer = prompt -> selection(List.of(handleOf(prompt, "주제 115 실습")), List.of(), List.of());

        Generated generated = f.generate("이번 주 계획");

        assertThat(f.selectionPrompts()).hasSize(1);
        assertThat(f.planPrompt()).contains("원문-115 고유문장").doesNotContain("원문-114 고유문장");
        MaterialSelectionSummary summary = generated.materialSelection();
        assertThat(summary.sections()).extracting(MaterialSelectionSummary.SectionPick::sectionId).containsExactly(1115L);
        assertThat(summary.sections().get(0).outcome()).isEqualTo("FULL");
        assertThat(summary.candidateTotal()).isEqualTo(240);
    }

    @Test
    void T2_같은_토픽의_문제_둘과_개념_설명_중_개념을_고르면_개념_원문이_들어간다() {
        f.material(10, "ds.pdf", 1L);
        f.topic(1, 1, "연결 리스트");
        f.section(101, 10, "삭제 문제 A", rolesJson("EXERCISE"), 3, "문제A-원문");
        f.section(102, 10, "삭제 문제 B", rolesJson("EXERCISE"), 4, "문제B-원문");
        f.section(103, 10, "노드 삭제의 개념", rolesJson("CONCEPT"), 5, "개념-원문");
        f.link(1, 1, 101);
        f.link(1, 1, 102);
        f.link(1, 1, 103);
        f.selectionAnswer = prompt -> selection(List.of(handleOf(prompt, "노드 삭제의 개념")), List.of(), List.of());

        f.generate("개념부터 이해하고 싶어");

        // 세 구간 모두 후보로 보였다(토픽당 2개 절단 없음, 역할 순위 없음).
        assertThat(f.selectionPrompts().get(0)).contains("삭제 문제 A").contains("삭제 문제 B").contains("노드 삭제의 개념");
        assertThat(f.planPrompt()).contains("개념-원문").doesNotContain("문제A-원문").doesNotContain("문제B-원문");
    }

    @Test
    void T3_토픽에_연결되지_않은_개념_PDF도_후보로_보이고_고르면_조회된다() {
        f.material(20, "개념정리.pdf", 1L);
        f.topic(1, 1, "스택");
        f.section(201, 20, "스택의 정의", rolesJson("CONCEPT"), 1, "스택개념-원문");
        f.section(202, 20, "참고 문헌", rolesJson("REFERENCE"), 2, "참고-원문");
        f.selectionAnswer = prompt -> selection(List.of(handleOf(prompt, "스택의 정의")), List.of(), List.of());

        Generated generated = f.generate(null);

        assertThat(f.selectionPrompts().get(0)).contains("토픽에 연결되지 않은 자료 · 개념정리.pdf")
                .contains("스택의 정의").contains("참고 문헌");
        assertThat(f.planPrompt()).contains("스택개념-원문");
        assertThat(generated.materialSelection().sections().get(0).topicId()).isNull();
    }

    @Nested
    class T4_이번_요청의_자료_지정 {

        @Test
        void origin_USER_연결_없이_지정한_자료가_선택_단계에_지정으로_보이고_고르면_조회된다() {
            f.material(30, "3주차 슬라이드.pdf", 1L);
            f.material(31, "다른 자료.pdf", 1L);
            f.topic(1, 1, "재귀");
            f.section(301, 30, "재귀 호출 추적", rolesJson("EXAMPLE"), 7, "지정자료-원문");
            f.section(311, 31, "무관한 구간", rolesJson("CONCEPT"), 1, "무관-원문");
            f.selectionAnswer = prompt -> {
                assertThat(prompt).contains("이번 요청에서 지정한 자료: 3주차 슬라이드.pdf");
                assertThat(prompt.lines().filter(l -> l.contains("재귀 호출 추적")).findFirst().orElseThrow())
                        .contains("이번 요청에서 지정한 자료");
                return selection(List.of(handleOf(prompt, "재귀 호출 추적")), List.of(), List.of());
            };

            Generated generated = f.generate(null, List.of(1L), List.of(), List.of(30L));

            assertThat(f.topicLinks).isEmpty();
            assertThat(f.planPrompt()).contains("지정자료-원문").contains("이번 요청에서 지정한 자료: 3주차 슬라이드.pdf");
            assertThat(generated.materialSelection().requestedMaterials())
                    .extracting(MaterialSelectionSummary.RequestedMaterialView::source).containsExactly("EXPLICIT");
        }

        @Test
        void 지시_문장의_파일_이름은_접근_가능한_목록에서만_찾고_같은_이름이_여럿이면_고르지_않는다() {
            f.course(2, "네트워크");
            f.material(40, "중간고사 정리.pdf", 1L);
            f.material(41, "과제 안내.pdf", 1L);
            f.material(42, "과제 안내.pdf", 2L);
            f.material(99, "다른사용자 자료.pdf");
            f.section(401, 40, "정렬 요약", rolesJson("SUMMARY"), 1, "정리-원문");

            Generated generated = f.generate("중간고사 정리 pdf 중심으로, 과제 안내도 봐줘");

            MaterialSelectionSummary summary = generated.materialSelection();
            assertThat(summary.requestedMaterials()).extracting(MaterialSelectionSummary.RequestedMaterialView::materialId)
                    .containsExactly(40L);
            assertThat(summary.requestedMaterials().get(0).source()).isEqualTo("INSTRUCTION");
            assertThat(summary.ambiguities()).hasSize(1);
            assertThat(summary.ambiguities().get(0).candidates()).hasSize(2);
        }

        @Test
        void 프로젝트에_연결되지_않은_지정_자료도_범위를_좁히지_않았으면_프로젝트_없는_묶음으로_검토된다() {
            f.material(50, "혼자 올린 자료.pdf");
            f.section(501, 50, "혼자 올린 구간", rolesJson("CONCEPT"), 1, "미연결지정-원문");
            f.selectionAnswer = prompt -> selection(List.of(handleOf(prompt, "혼자 올린 구간")), List.of(), List.of());

            f.generate(null, List.of(), List.of(), List.of(50L));

            assertThat(f.selectionPrompts().get(0)).contains("프로젝트에 연결되지 않은 지정 자료");
            assertThat(f.planPrompt()).contains("미연결지정-원문").contains("(프로젝트 없음)");
        }

        @Test
        void 다른_사용자의_자료나_범위_밖_자료를_지정하면_400이다() {
            f.material(PlanSelectionFixture.OTHER_USER, 60, "남의 자료.pdf", 1L);
            f.course(3, "다른 과목");
            f.material(61, "다른 과목 자료.pdf", 3L);

            assertThatThrownBy(() -> f.generate(null, List.of(1L), List.of(), List.of(60L)))
                    .isInstanceOf(BadRequestException.class)
                    .extracting(e -> ((BadRequestException) e).getErrorCode()).isEqualTo(ErrorCode.PLAN_REQUESTED_MATERIAL_INVALID);
            assertThatThrownBy(() -> f.generate(null, List.of(1L), List.of(), List.of(61L)))
                    .isInstanceOf(BadRequestException.class);
        }
    }

    @Test
    void T5_개념_먼저와_문제_먼저에서_모델이_고른_순서를_서버가_역할_순서로_바꾸지_않는다() {
        f.material(10, "ds.pdf", 1L);
        f.topic(1, 1, "큐");
        f.section(101, 10, "큐 문제", rolesJson("EXERCISE"), 1, "큐문제-원문");
        f.section(102, 10, "큐 개념", rolesJson("CONCEPT"), 2, "큐개념-원문");
        f.link(1, 1, 101);
        f.link(1, 1, 102);

        f.selectionAnswer = prompt -> selection(List.of(handleOf(prompt, "큐 개념"), handleOf(prompt, "큐 문제")), List.of(), List.of());
        f.generate("개념 먼저");
        String conceptFirst = f.planPrompt();
        assertThat(conceptFirst.indexOf("큐개념-원문")).isLessThan(conceptFirst.indexOf("큐문제-원문"));

        f.calls.clear();
        f.selectionAnswer = prompt -> selection(List.of(handleOf(prompt, "큐 문제")), List.of(), List.of());
        f.generate("문제 먼저");
        assertThat(f.planPrompt()).contains("큐문제-원문").doesNotContain("큐개념-원문");
    }

    @Test
    void T6_마감_진행_중_첫_미학습은_선택과_무관하게_판단에_남고_실행_항목으로_강제되지_않는다() {
        f.material(10, "ds.pdf", 1L);
        f.topic(1, 1, null, "배열", "1주차", TopicProgressStatus.LEARNED);
        f.topic(1, 2, null, "연결 리스트", "2주차", TopicProgressStatus.IN_PROGRESS);
        f.topic(1, 3, null, "스택", "3주차", TopicProgressStatus.NOT_STARTED);
        f.topic(1, 4, null, "해시", "9주차", TopicProgressStatus.NOT_STARTED);
        f.section(101, 10, "해시 충돌 설명", rolesJson("CONCEPT"), 9, "해시-원문");
        f.section(102, 10, "과제 3 해시 구현", rolesJson("ASSIGNMENT"), 10, "과제3-원문");
        f.link(1, 4, 101);
        f.link(1, 4, 102);
        f.assignment(900, 1, 4L, 102L, "과제 3 해시 구현", LocalDate.of(2026, 9, 18), false);
        f.selectionAnswer = prompt -> selection(List.of(handleOf(prompt, "해시 충돌 설명")), List.of(), List.of());

        Generated generated = f.generate(null);

        String plan = f.planPrompt();
        assertThat(plan).contains("[판단에 필요한 사실]")
                .contains("연결 리스트 (2주차) · 진행 중")
                .contains("스택 (3주차) · ← 첫 미학습")
                .contains("과제: 과제 3 해시 구현 · 마감 2026-09-18(원문에 명시)");
        // 과제 구간 원문은 모델이 고르지 않았으므로 실리지 않는다. 판단 사실(마감)은 남는다.
        assertThat(plan).doesNotContain("과제3-원문").contains("해시-원문");
        // 서버는 사실을 실행 항목으로 넣지 않는다 — 모델이 낸 항목 수 그대로다.
        assertThat(generated.items()).hasSize(1);
    }

    @Test
    void T7_후보가_예산을_넘으면_묶음으로_접히고_펼쳐서_뒤쪽_구간에_접근한다() {
        f.material(10, "big.pdf", 1L);
        for (long root = 1; root <= 30; root++) {
            f.topic(1, root, null, "단원 " + root, root + "주차", TopicProgressStatus.LEARNED);
            for (long child = 1; child <= 5; child++) {
                long id = root * 100 + child;
                f.topic(1, id, root, "단원 " + root + "-" + child + " 세부 주제의 긴 제목", null, TopicProgressStatus.LEARNED);
                f.section(id * 10, 10, "구간 " + root + "-" + child + " 연습과 풀이 설명", rolesJson("EXERCISE", "EXAMPLE"),
                        (int) id, "원문-" + root + "-" + child);
                f.link(1, id, id * 10);
            }
        }
        ReflectionTestUtils.setField(f.selector, "inputTokenBudget", 6000);
        AtomicInteger round = new AtomicInteger();
        f.selectionAnswer = prompt -> {
            if (round.getAndIncrement() == 0) {
                assertThat(prompt).contains("[접힌 묶음").doesNotContain("구간 28-3 연습과 풀이 설명");
                String group = handleOf(prompt, "단원 28 —");
                return selection(List.of(), List.of(), List.of(group));
            }
            assertThat(prompt).contains("[펼친 묶음]").contains("구간 28-3 연습과 풀이 설명");
            return selection(List.of(handleOf(prompt, "구간 28-3 연습과 풀이 설명")), List.of(), List.of());
        };

        Generated generated = f.generate(null);

        assertThat(f.selectionPrompts()).hasSize(2);
        assertThat(f.planPrompts()).hasSize(1);
        assertThat(f.planPrompt()).contains("원문-28-3");
        MaterialSelectionSummary summary = generated.materialSelection();
        assertThat(summary.expanded()).isTrue();
        assertThat(summary.selectionCalls()).isEqualTo(PlanMaterialSelector.MAX_SELECTION_CALLS);
        assertThat(summary.mode()).startsWith("FOLDED");
        assertThat(summary.unreviewed()).isNotEmpty();
        assertThat(summary.unreviewed()).noneMatch(u -> u.title().equals("단원 28"));
        assertThat(summary.selectionInputTokens()).allMatch(t -> t <= 6000);
    }

    @Nested
    class T8_돌아온_id의_검증 {

        @BeforeEach
        void data() {
            f.material(10, "ds.pdf", 1L);
            f.topic(1, 1, "트리");
            f.section(101, 10, "트리 순회", rolesJson("CONCEPT"), 1, "순회-원문");
            f.section(102, 10, "트리 높이", rolesJson("EXERCISE"), 2, "높이-원문");
            f.link(1, 1, 101);
            f.link(1, 1, 102);
        }

        @Test
        void 보여_주지_않은_id는_버리고_센다() {
            f.selectionAnswer = prompt -> selection(List.of(handleOf(prompt, "트리 순회"), "m9999", "1234"), List.of(), List.of());

            Generated generated = f.generate(null);

            assertThat(generated.materialSelection().unknownIds()).isEqualTo(2);
            assertThat(generated.materialSelection().sections()).hasSize(1);
        }

        @Test
        void 선택과_조회_사이에_삭제_변경_연결_해제된_구간은_싣지_않고_이유를_남긴다() {
            f.material(11, "changed.pdf", 1L);
            f.section(111, 11, "바뀔 구간", rolesJson("CONCEPT"), 1, "바뀐-원문");
            f.material(12, "unlinked.pdf", 1L);
            f.section(121, 12, "끊길 구간", rolesJson("CONCEPT"), 1, "끊긴-원문");
            f.selectionAnswer = prompt -> {
                String deleted = handleOf(prompt, "트리 순회");
                String changed = handleOf(prompt, "바뀔 구간");
                String unlinked = handleOf(prompt, "끊길 구간");
                String kept = handleOf(prompt, "트리 높이");
                f.materials.get(10L).setStatus(MaterialStatus.DELETED);        // ds.pdf 삭제(트리 순회·높이 둘 다)
                f.materials.get(11L).setFileHash("h11-new");                   // 파일 교체
                f.materialLinks.removeIf(l -> l.getMaterialId() == 12L);       // 프로젝트 연결 해제
                return selection(List.of(deleted, changed, unlinked, kept), List.of(), List.of());
            };

            Generated generated = f.generate(null);

            assertThat(generated.materialSelection().sections())
                    .extracting(MaterialSelectionSummary.SectionPick::outcome)
                    .containsExactly("DROPPED_DELETED", "DROPPED_CHANGED", "DROPPED_SCOPE", "DROPPED_DELETED");
            assertThat(f.planPrompt()).doesNotContain("순회-원문").doesNotContain("바뀐-원문").doesNotContain("끊긴-원문")
                    .contains("삭제·변경됐거나 범위를 벗어나 싣지 않았다");
        }

        @Test
        void 조회기는_다른_사용자의_구간을_읽지_않는다() {
            f.material(PlanSelectionFixture.OTHER_USER, 70, "남의 자료.pdf", 1L);
            f.section(701, 70, "남의 구간", rolesJson("CONCEPT"), 1, "남의-원문");

            List<PlanMaterialRetriever.Retrieved> retrieved = f.retriever.retrieve(PlanSelectionFixture.USER,
                    List.of(new PlanMaterialRetriever.Target("m1", 701L, "h70", 1L, null, null)),
                    java.util.Set.of(1L), java.util.Set.of());

            assertThat(retrieved.get(0).outcome()).isEqualTo(PlanMaterialRetriever.Outcome.DROPPED_DELETED);
            assertThat(retrieved.get(0).units()).isEmpty();
        }
    }

    @Nested
    class T9_실패_잘못된_응답_빈_선택의_구분 {

        @BeforeEach
        void data() {
            f.material(10, "ds.pdf", 1L);
            f.topic(1, 1, "그래프");
            f.section(101, 10, "그래프 탐색", rolesJson("CONCEPT"), 1, "탐색-원문");
            f.link(1, 1, 101);
        }

        @Test
        void 선택_호출_실패는_재시도_가능한_오류이고_서버_순위로_대체하지_않는다() {
            f.selectionError = new RuntimeException("timeout");

            assertThatThrownBy(() -> f.generate(null))
                    .isInstanceOf(ServiceUnavailableException.class)
                    .extracting(e -> ((ServiceUnavailableException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PLAN_MATERIAL_SELECTION_FAILED);
            assertThat(f.planPrompts()).isEmpty();
        }

        @Test
        void 구조가_없거나_보여_주지_않은_id만_낸_응답은_잘못된_응답이다() {
            f.selectionAnswer = prompt -> "그냥 문장";
            assertThatThrownBy(() -> f.generate(null))
                    .extracting(e -> ((ServiceUnavailableException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PLAN_MATERIAL_SELECTION_INVALID);

            f.selectionAnswer = prompt -> selection(List.of("m777", "t888"), List.of(), List.of());
            assertThatThrownBy(() -> f.generate(null))
                    .extracting(e -> ((ServiceUnavailableException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PLAN_MATERIAL_SELECTION_INVALID);
            assertThat(f.planPrompts()).isEmpty();
        }

        @Test
        void 정상적인_빈_선택은_계획_단계로_넘어가고_원문은_없다() {
            f.selectionAnswer = prompt -> PlanSelectionFixture.emptySelection();

            Generated generated = f.generate(null);

            assertThat(generated.materialSelection().status()).isEqualTo("EMPTY");
            assertThat(f.planPrompts()).hasSize(1);
            assertThat(f.planPrompt()).doesNotContain("[고른 자료 구간 — 저장된 원문에서 읽음]").doesNotContain("탐색-원문")
                    .contains("이번에 읽은 원문은 없다");
        }
    }

    @Test
    void T10_선택_호출과_계획_호출은_분리되고_최종_refIds는_계획_호출에_준_근거만_참조한다() {
        f.material(10, "ds.pdf", 1L);
        f.topic(1, 1, "힙");
        f.section(101, 10, "힙 삽입", rolesJson("EXERCISE"), 1, "힙삽입-원문");
        f.section(102, 10, "힙 삭제", rolesJson("EXERCISE"), 2, "힙삭제-원문");
        f.link(1, 1, 101);
        f.link(1, 1, 102);
        List<String> selectionHandles = new ArrayList<>();
        f.selectionAnswer = prompt -> {
            selectionHandles.add(handleOf(prompt, "힙 삭제"));
            return selection(List.of(handleOf(prompt, "힙 삽입")), List.of(), List.of());
        };
        PlanSelectionFixture g = f;

        Generated first = g.generate(null);
        String plan = g.planPrompt();
        String insertRef = refOf(plan, "힙 삽입");
        assertThat(insertRef).isNotNull();

        // 계획 모델이 계획 프롬프트의 실제 인용, 선택 단계의 핸들, 없는 인용을 섞어 낸다.
        g.calls.clear();
        g.planJson = "{\"title\":\"t\",\"goalSummary\":null,\"items\":[{\"title\":\"자료구조 · 힙\",\"description\":\"한다 · 완료: 끝\","
                + "\"expectedMinutes\":30,\"priority\":\"SHOULD\",\"courseId\":1,\"scheduledDate\":null,\"reason\":\"r\","
                + "\"refIds\":[\"" + insertRef + "\",\"" + selectionHandles.get(0) + "\",\"s999\"]}]}";
        Generated generated = g.generate(null);

        assertThat(g.calls).hasSize(2);
        assertThat(PlanSelectionFixture.isSelection(g.calls.get(0)[0])).isTrue();
        assertThat(PlanSelectionFixture.isSelection(g.calls.get(1)[0])).isFalse();
        PlanItemEvidence evidence = generated.itemEvidence().get(0);
        assertThat(evidence.refIds()).containsExactly(insertRef);
        assertThat(evidence.unknownRefCount()).isEqualTo(2);
        List<ProvidedSource> sections = generated.provenance().providedSources().stream()
                .filter(s -> s.sourceType() == ProvenanceSourceType.MATERIAL_SECTION).toList();
        assertThat(sections).extracting(ProvidedSource::sourceId).containsExactly(101L);
        assertThat(sections.get(0).providedValue()).containsKey("retrievedRange").containsEntry("retrieval", "FULL");
        assertThat(generated.provenance().serverCalculations())
                .extracting(ServerCalculation::kind).contains(ServerCalculation.ServerCalculationKind.MATERIAL_SELECTION);
        assertThat(first.materialSelection().selectionCalls()).isEqualTo(1);
    }

    @Test
    void 이미_알아요와_이번만_제외는_후보에서_빠지고_사용자_수정으로_남는다() {
        f.material(10, "ds.pdf", 1L);
        f.topic(1, 1, null, "배열", null, TopicProgressStatus.NOT_STARTED, TopicUserMark.KNOWN);
        f.topic(1, 2, "포인터");
        f.topic(1, 3, "구조체");
        f.section(101, 10, "배열 인덱스", rolesJson("CONCEPT"), 1, "배열-원문");
        f.link(1, 1, 101);

        f.generate(null, List.of(), List.of(3L), List.of());

        String prompt = f.selectionPrompts().get(0);
        assertThat(prompt).contains("이미 알아요 표시 1개(배열)").contains("이번 계획에서만 제외 1개(구조체)");
        assertThat(handleOf(prompt, "배열 인덱스")).isNull();
        assertThat(prompt).contains("포인터 · ← 첫 미학습");
    }
}
