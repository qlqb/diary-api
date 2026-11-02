package com.jungwoo.project.memo.ai.evidence;

import com.jungwoo.project.memo.material.TextUnitHit;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 2026-10-03 코드 리뷰(Codex)에서 나온 근거 조회 결함의 회귀 테스트. */
class EvidenceReviewFixesTest {

    @Test
    void 머리_행과_앞줄이_길어도_걸린_시험_행은_잘리지_않는다() {
        StringBuilder text = new StringBuilder("주차별 계획\n[표 시작]\n| 주차 | 날짜 | 내용 " + "(설명) ".repeat(60) + "|\n");
        for (int i = 1; i <= 6; i++) {
            text.append("| ").append(i).append("주차 | 9/").append(i).append(" | ").append("진도 설명 ".repeat(60)).append("|\n");
        }
        text.append("| 7주차 | 10/19~10/23 | 중간고사 |\n");
        text.append("| 8주차 | 10/27 | ").append("진도 설명 ".repeat(60)).append("|\n[표 끝]\n");
        QueryTerms q = QueryTerms.of("시험", List.of(), null, List.of());

        PassageExtractor.Passage p = PassageExtractor.extract(text.toString(), q.terms(), 600);

        assertThat(p.text()).contains("| 7주차 | 10/19~10/23 | 중간고사 |");
        assertThat(p.text().length()).isLessThanOrEqualTo(601);
    }

    @Test
    void 한_자료가_검색_상위를_다_차지해도_다른_자료의_단위가_후보에_든다() {
        List<TextUnitHit> hits = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            hits.add(hit(100L + i, 1L)); // 큰 자료 하나가 50쪽 모두 걸림
        }
        hits.add(hit(900L, 2L)); // 다른 과목 자료의 시험 안내(같은 걸린 수, 뒤쪽)

        List<Long> picked = ConsultEvidenceService.spreadAcrossMaterials(hits, 10);

        assertThat(picked).hasSize(10).contains(900L);
    }

    @Test
    void 긴_줄에서_띄어쓰기가_다르게_걸린_위치_주변을_남긴다() {
        // 걸리는 것은 확장어 "강의시간"뿐이다(원문은 띄어 쓴 "강의 시간").
        String line = "안내 ".repeat(300) + "강의 시간: 목 10:00~11:50" + " 끝".repeat(100);
        QueryTerms q = QueryTerms.of("시간표", List.of(), null, List.of());

        String cut = PassageExtractor.cutLine(line, q.terms(), 200);

        assertThat(cut).contains("강의 시간: 목 10:00~11:50");
    }

    private static TextUnitHit hit(long unitId, long materialId) {
        TextUnitHit h = new TextUnitHit();
        h.setUnitId(unitId);
        h.setMaterialId(materialId);
        h.setHits(3);
        return h;
    }
}
