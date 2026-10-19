package com.jungwoo.project.memo.plan.help;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시작 도움의 계약: 첫 행동을 구체화할 뿐 범위를 바꾸지 않고, 근거 없는 자료 위치를 보태지 않는다.
 */
class StartHelpServiceTest {

    @Test
    void 인용_구간이_없으면_모델이_쓴_자료_위치를_버린다() {
        StartHelpResponse.Stored raw = new StartHelpResponse.Stored("노트를 펴고 정의를 적는다", null,
                "교재 p.45의 예제 3", false, null);

        StartHelpResponse.Stored out = StartHelpService.sanitize(raw, false);

        assertThat(out.where()).as("없는 쪽수를 지어내지 않는다").isNull();
        assertThat(out.grounded()).isFalse();
        assertThat(out.firstAction()).isEqualTo("노트를 펴고 정의를 적는다");
    }

    @Test
    void 시작_활동은_5에서_15분_단계_넷까지로_자른다() {
        StartHelpResponse.Stored raw = new StartHelpResponse.Stored("예제를 따라 친다",
                new StartHelpResponse.Starter("워밍업", 40, List.of("a", "b", " ", "c", "d", "e")), "p.3", false, true);

        StartHelpResponse.Stored out = StartHelpService.sanitize(raw, true);

        assertThat(out.starter().minutes()).isEqualTo(15);
        assertThat(out.starter().steps()).containsExactly("a", "b", "c", "d");
        assertThat(out.where()).isEqualTo("p.3");
    }

    @Test
    void 범위_변경_요청은_표시만_하고_도움은_그대로_남긴다() {
        StartHelpResponse.Stored raw = new StartHelpResponse.Stored("오늘은 1번만 먼저 본다", null, null, true, null);

        StartHelpResponse.Stored out = StartHelpService.sanitize(raw, true);

        assertThat(out.scopeChangeRequested()).isTrue();
        assertThat(out.starter()).isNull();
    }
}
