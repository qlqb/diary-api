package com.jungwoo.project.memo.plan.scope;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 요청 범위의 부분별 확보·반영 판정. 이번 사례(초안 4453)의 누락 유형을 그대로 재현한다. */
class ScopeVerdictTest {

    private static final long NET = 937L;
    private static final long APP = 939L;
    private static final long ENG = 940L;

    private static RequestScope.Part part(String id, String label, List<Long> materials, boolean noMaterial, boolean user) {
        return new RequestScope.Part(id, label, materials, noMaterial, user);
    }

    private static RequestScope scope(String id, Long course, String role, boolean verified, RequestScope.Part... parts) {
        return new RequestScope(id, course, "과목", role, "목표", "범위", "구절", verified ? "문장" : null, verified,
                List.of(parts));
    }

    // 구간 → 자료: 2주차(자료 245)의 구간 21, 6주차(460)의 31, 7주차(459)의 41, 어댑터뷰(441)의 51
    private static final Map<Long, Long> SECTION_MATERIAL = Map.of(21L, 245L, 31L, 460L, 41L, 459L, 51L, 441L);

    @Test
    void 보였지만_고르지_않은_주차와_예산으로_빠진_주차와_반영된_주차를_구분한다() {
        RequestScope net = scope("S1", NET, RequestScope.OPTIONAL, true,
                part("S1.P1", "2주차", List.of(245L), false, false),
                part("S1.P2", "6주차", List.of(460L), false, false),
                part("S1.P3", "7주차 56장까지", List.of(459L), false, false));

        ScopeReport report = ScopeVerdict.judge(ScopeReport.EXTRACTED, List.of(net), SECTION_MATERIAL,
                Set.of(21L, 31L, 41L), Map.of(41L, false), Map.of("s80", 41L), Map.of(),
                List.of(new ScopeVerdict.ItemRef(NET, List.of("s80"))), List.of());

        assertThat(report.parts()).extracting(ScopeReport.PartState::acquisition)
                .containsExactly("SHOWN_NOT_SELECTED", "SHOWN_NOT_SELECTED", "EXCERPT_ONLY");
        assertThat(report.parts()).extracting(ScopeReport.PartState::reflection)
                .containsExactly("UNHANDLED", "UNHANDLED", "ITEM");
        assertThat(report.remaining()).containsExactly("S1.P1", "S1.P2");
        // 선택 범위만 남았다 — 필수 미충족은 아니지만 전체 완료도 아니다.
        assertThat(report.requiredUnmet()).isFalse();
        assertThat(report.completion()).isEqualTo("REQUIRED_ONLY");
    }

    @Test
    void 앱에_자료가_없어도_사용자_자료로_하는_연습이면_반영이고_자료도_연습도_없으면_미충족이다() {
        RequestScope app = scope("S1", APP, RequestScope.REQUIRED, true,
                part("S1.P1", "RecyclerView", List.of(441L), false, false),
                part("S1.P2", "SQLite", List.of(), true, false),
                part("S1.P3", "배포 PDF로 코드 빈칸", List.of(), false, true));

        ScopeReport report = ScopeVerdict.judge(ScopeReport.EXTRACTED, List.of(app), SECTION_MATERIAL,
                Set.of(51L), Map.of(), Map.of(), Map.of("s9", "S1.P3"),
                List.of(new ScopeVerdict.ItemRef(APP, List.of("s9"))), List.of());

        assertThat(report.parts()).extracting(ScopeReport.PartState::acquisition)
                .containsExactly("SHOWN_NOT_SELECTED", "NO_MATERIAL_IN_APP", "NO_MATERIAL_IN_APP");
        assertThat(report.parts()).extracting(ScopeReport.PartState::reflection)
                .containsExactly("UNHANDLED", "UNHANDLED", "PRACTICE_WITH_USER_MATERIAL");
        assertThat(report.requiredUnmet()).isTrue();
        assertThat(report.completion()).isEqualTo("NOT_COMPLETE");
    }

    @Test
    void 미룬_부분은_이유와_함께_남고_미충족이다() {
        RequestScope eng = scope("S1", ENG, RequestScope.REQUIRED, true,
                part("S1.P1", "교재에 표시한 시험 범위", List.of(), false, true));

        ScopeReport report = ScopeVerdict.judge(ScopeReport.EXTRACTED, List.of(eng), SECTION_MATERIAL, Set.of(), Map.of(),
                Map.of(), Map.of("s5", "S1.P1"), List.of(),
                List.of(new ScopeVerdict.DeferredRef("오늘 시간이 부족하다", List.of("s5"))));

        assertThat(report.parts().get(0).reflection()).isEqualTo("DEFERRED");
        assertThat(report.parts().get(0).note()).isEqualTo("오늘 시간이 부족하다");
        assertThat(report.requiredUnmet()).isTrue();
    }

    @Test
    void 다른_과목의_항목과_과목_미확정_범위의_연결은_충족이_아니다() {
        RequestScope app = scope("S1", APP, RequestScope.REQUIRED, true, part("S1.P1", "Navigation", List.of(), false, true));
        RequestScope unknown = scope("S2", null, RequestScope.REQUIRED, true, part("S2.P1", "전체 복습", List.of(), false, false));

        ScopeReport report = ScopeVerdict.judge(ScopeReport.EXTRACTED, List.of(app, unknown), SECTION_MATERIAL, Set.of(),
                Map.of(), Map.of(), Map.of("s1", "S1.P1", "s2", "S2.P1"),
                List.of(new ScopeVerdict.ItemRef(NET, List.of("s1")), new ScopeVerdict.ItemRef(NET, List.of("s2"))),
                List.of());

        assertThat(report.parts()).extracting(ScopeReport.PartState::reflection).containsExactly("UNHANDLED", "UNHANDLED");
        assertThat(report.parts().get(1).note()).contains("과목을 정하지 못한");
    }

    @Test
    void 완료_표시는_검증된_범위로만_정하고_확인할_수_없음을_충족으로_보지_않는다() {
        RequestScope done = scope("S1", NET, RequestScope.REQUIRED, true, part("S1.P1", "7주차", List.of(459L), false, false));
        RequestScope optionalLeft = scope("S2", APP, RequestScope.OPTIONAL, true,
                part("S2.P1", "Navigation", List.of(), false, true));
        RequestScope unverified = scope("S3", ENG, RequestScope.REQUIRED, false, part("S3.P1", "Unit 6까지", List.of(), false, true));
        List<ScopeVerdict.ItemRef> item = List.of(new ScopeVerdict.ItemRef(NET, List.of("s80")));
        Map<String, Long> refs = Map.of("s80", 41L);

        // 필수 전부 반영 + 선택 범위 보류 → 필수만 반영
        assertThat(ScopeVerdict.judge("EXTRACTED", List.of(done, optionalLeft), SECTION_MATERIAL, Set.of(), Map.of(41L, true),
                refs, Map.of(), item, List.of()).completion()).isEqualTo("REQUIRED_ONLY");
        // 모든 범위 검증·반영 완료 → 전체
        assertThat(ScopeVerdict.judge("EXTRACTED", List.of(done), SECTION_MATERIAL, Set.of(), Map.of(41L, true),
                refs, Map.of(), item, List.of()).completion()).isEqualTo("ALL");
        // 전부 검증 실패 → 알 수 없음(충족 아님), 확인 필요로 남는다
        ScopeReport none = ScopeVerdict.judge("EXTRACTED", List.of(unverified), SECTION_MATERIAL, Set.of(), Map.of(),
                Map.of(), Map.of(), List.of(), List.of());
        assertThat(none.completion()).isEqualTo("UNKNOWN");
        assertThat(none.needsConfirm()).containsExactly("S3");
        assertThat(none.requiredUnmet()).isFalse(); // 근거 없는 필수 범위는 필수 판정에 넣지 않는다
        // 검증된 범위는 다 됐지만 확인 필요가 있으면 전체 반영이 아니다
        assertThat(ScopeVerdict.judge("EXTRACTED", List.of(done, unverified), SECTION_MATERIAL, Set.of(), Map.of(41L, true),
                refs, Map.of(), item, List.of()).completion()).isEqualTo("REQUIRED_ONLY");
        assertThat(ScopeVerdict.judge("NONE", List.of(), SECTION_MATERIAL, Set.of(), Map.of(), Map.of(), Map.of(),
                List.of(), List.of()).completion()).isEqualTo("UNKNOWN");
    }
    @Test
    void 한_자료가_여러_부분에_걸치면_구간_인용만으로는_어느_부분인지_정하지_않는다() {
        // 같은 PDF(441)에 Navigation과 SQLite가 있다.
        RequestScope app = scope("S1", APP, RequestScope.REQUIRED, true,
                part("S1.P1", "Navigation", List.of(441L), false, false),
                part("S1.P2", "SQLite", List.of(441L), false, false));
        ScopeReport sourceOnly = ScopeVerdict.judge(ScopeReport.EXTRACTED, List.of(app), SECTION_MATERIAL, Set.of(51L),
                Map.of(51L, true), Map.of("s51", 51L), Map.of("s1", "S1.P1", "s2", "S1.P2"),
                List.of(new ScopeVerdict.ItemRef(APP, List.of("s51"))), List.of());
        assertThat(sourceOnly.parts()).extracting(ScopeReport.PartState::reflection).containsExactly("UNHANDLED", "UNHANDLED");

        ScopeReport withPart = ScopeVerdict.judge(ScopeReport.EXTRACTED, List.of(app), SECTION_MATERIAL, Set.of(51L),
                Map.of(51L, true), Map.of("s51", 51L), Map.of("s1", "S1.P1", "s2", "S1.P2"),
                List.of(new ScopeVerdict.ItemRef(APP, List.of("s51", "s1"))), List.of());
        assertThat(withPart.parts()).extracting(ScopeReport.PartState::reflection).containsExactly("ITEM", "UNHANDLED");
        assertThat(withPart.completion()).isEqualTo("NOT_COMPLETE");
    }

    @Test
    void 부분_줄만_인용하고_원문도_사용자_자료도_없으면_충족이_아니다() {
        RequestScope app = scope("S1", APP, RequestScope.REQUIRED, true, part("S1.P1", "SQLite", List.of(), true, false));
        ScopeReport report = ScopeVerdict.judge(ScopeReport.EXTRACTED, List.of(app), SECTION_MATERIAL, Set.of(), Map.of(),
                Map.of(), Map.of("s1", "S1.P1"), List.of(new ScopeVerdict.ItemRef(APP, List.of("s1"))), List.of());
        assertThat(report.parts().get(0).reflection()).isEqualTo("UNHANDLED");
        assertThat(report.parts().get(0).note()).contains("원문 근거도 사용자가 가진 자료도 없다");
    }

    @Test
    void 골랐지만_빠진_구간은_고르지_않음이_아니라_미확보와_사유로_남고_추출_누락이_있으면_완료를_말하지_않는다() {
        RequestScope net = scope("S1", NET, RequestScope.REQUIRED, true, part("S1.P1", "6주차", List.of(460L), false, false));
        RequestScope done = scope("S2", NET, RequestScope.REQUIRED, true, part("S2.P1", "7주차", List.of(459L), false, false));
        ScopeReport report = ScopeVerdict.judge(ScopeReport.EXTRACTED, false, List.of(net), SECTION_MATERIAL, Set.of(31L),
                Map.of(31L, "입력 한도"), Map.of(), Map.of(), Map.of(), List.of(), List.of());
        assertThat(report.parts().get(0).acquisition()).isEqualTo("AVAILABLE_NOT_ACQUIRED");
        assertThat(report.parts().get(0).acquisitionNote()).contains("입력 한도");

        ScopeReport partial = ScopeVerdict.judge(ScopeReport.EXTRACTED, true, List.of(done), SECTION_MATERIAL, Set.of(),
                Map.of(), Map.of(41L, true), Map.of("s80", 41L), Map.of(),
                List.of(new ScopeVerdict.ItemRef(NET, List.of("s80"))), List.of());
        assertThat(partial.completion()).isEqualTo("UNKNOWN");
        assertThat(partial.incomplete()).isTrue();
    }
    @Test
    void 함께_쓰는_자료의_확보와_미룸은_다른_부분으로_번지지_않는다() {
        RequestScope app = scope("S1", APP, RequestScope.REQUIRED, true,
                part("S1.P1", "Navigation", List.of(441L), false, false),
                part("S1.P2", "SQLite", List.of(441L), false, false));
        ScopeReport report = ScopeVerdict.judge(ScopeReport.EXTRACTED, false, List.of(app), SECTION_MATERIAL, Set.of(51L),
                Map.of(), Map.of(51L, true), Map.of("s51", 51L), Map.of("s1", "S1.P1", "s2", "S1.P2"), List.of(),
                List.of(new ScopeVerdict.DeferredRef("Navigation은 내일", List.of("s1", "s51"))));
        // 공유 자료의 구간이 실려도 어느 부분 것인지 모른다.
        assertThat(report.parts()).extracting(ScopeReport.PartState::acquisition)
                .containsExactly("AVAILABLE_NOT_ACQUIRED", "AVAILABLE_NOT_ACQUIRED");
        assertThat(report.parts().get(1).acquisitionNote()).contains("같은 자료");
        // Navigation을 미룬 것이 SQLite까지 미룬 것으로 번지지 않는다.
        assertThat(report.parts()).extracting(ScopeReport.PartState::reflection).containsExactly("DEFERRED", "UNHANDLED");
    }
    @Test
    void 과목_전체를_미룬_이유는_그_과목_범위의_부분에_이어지고_다른_과목에는_번지지_않는다() {
        RequestScope net = scope("S1", NET, RequestScope.OPTIONAL, true,
                part("S1.P1", "2주차", List.of(245L), false, false), part("S1.P2", "6주차", List.of(460L), false, false));
        RequestScope app = scope("S2", APP, RequestScope.OPTIONAL, true, part("S2.P1", "Navigation", List.of(), false, true));
        RequestScope netNamed = new RequestScope("S1", NET, "네트워크프로그래밍", RequestScope.OPTIONAL, "목표", "범위", "q", "q", true,
                net.parts());
        RequestScope appNamed = new RequestScope("S2", APP, "스마트앱프로젝트", RequestScope.OPTIONAL, "목표", "범위", "q", "q", true,
                app.parts());
        ScopeReport report = ScopeVerdict.judge(ScopeReport.EXTRACTED, List.of(netNamed, appNamed), SECTION_MATERIAL, Set.of(),
                Map.of(), Map.of(), Map.of(), List.of(),
                List.of(new ScopeVerdict.DeferredRef("네트워크프로그래밍", "오늘 남은 60분에 넣기 어렵다", List.of())));

        assertThat(report.parts()).extracting(ScopeReport.PartState::reflection)
                .containsExactly("DEFERRED", "DEFERRED", "UNHANDLED");
        assertThat(report.parts().get(0).note()).isEqualTo("오늘 남은 60분에 넣기 어렵다");
    }
    @Test
    void 서버가_정한_과목_제외_이유는_과목_id로_그_범위의_부분에_이어진다() {
        RequestScope app = new RequestScope("S1", APP, "스마트앱프로젝트", RequestScope.OPTIONAL, "목표", "범위", "q", "q", true,
                List.of(part("S1.P1", "Navigation", List.of(441L), false, false), part("S1.P2", "SQLite", List.of(), true, false)));
        RequestScope net = new RequestScope("S2", NET, "네트워크프로그래밍", RequestScope.OPTIONAL, "목표", "범위", "q", "q", true,
                List.of(part("S2.P1", "2주차", List.of(245L), false, false)));
        ScopeReport report = ScopeVerdict.judge(ScopeReport.EXTRACTED, false, List.of(app, net), SECTION_MATERIAL, Set.of(),
                Map.of(), Map.of(), Map.of(), Map.of(), List.of(),
                List.of(new ScopeVerdict.DeferredRef("스마트앱프로젝트의 Navigation·SQLite 전체", "다음 기간", List.of())),
                Map.of(APP, "60분 예산에서 우선 과목을 먼저 다룬다"));

        assertThat(report.parts()).extracting(ScopeReport.PartState::reflection)
                .containsExactly("DEFERRED", "DEFERRED", "UNHANDLED"); // 네트워크는 제외되지 않았다
        assertThat(report.parts().get(0).note()).contains("이 과목을 뺐다").contains("60분 예산");
    }
    @Test
    void 특정_부분을_인용한_미룸은_제목이_과목명이어도_다른_부분으로_번지지_않는다() {
        RequestScope net = new RequestScope("S1", NET, "네트워크프로그래밍", RequestScope.OPTIONAL, "목표", "범위", "q", "q", true,
                List.of(part("S1.P1", "2주차", List.of(245L), false, false), part("S1.P2", "6주차", List.of(460L), false, false)));
        ScopeReport report = ScopeVerdict.judge(ScopeReport.EXTRACTED, false, List.of(net), SECTION_MATERIAL, Set.of(),
                Map.of(), Map.of(), Map.of(), Map.of("s2", "S1.P2"), List.of(),
                List.of(new ScopeVerdict.DeferredRef("네트워크프로그래밍", "6주차는 내일", List.of("s2"))), Map.of());
        assertThat(report.parts()).extracting(ScopeReport.PartState::reflection).containsExactly("UNHANDLED", "DEFERRED");
    }
}
