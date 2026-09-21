package com.jungwoo.project.memo.learning.tidy.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/** 프로젝트 정리 요청들. */
public final class ProjectTidyRequests {

    private ProjectTidyRequests() {
    }

    /**
     * 검토 중 고친 것을 저장한다. 자동 저장이 이 경로로 들어온다.
     *
     * <p><b>트리를 바꾸지 않는다.</b> 검토 초안을 남기는 것뿐이다.
     *
     * @param editRevision 마지막으로 읽은 편집 판. 어긋나면 409 — 다른 탭이 먼저 저장한 것을 덮지 않는다
     */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class SaveEdits {
        @NotNull
        private Long editRevision;
        /** changeId → {excluded, title}. 전체를 보낸다(부분 갱신이 아니다). */
        private Map<String, Edit> edits;

        @Getter
        @Setter
        @NoArgsConstructor
        public static class Edit {
            private boolean excluded;
            private String title;
        }
    }

    /**
     * 고른 변경을 실제로 적용한다.
     *
     * <p>세 판 번호를 모두 싣는다. 하나라도 어긋나면 적용하지 않고 최신 상태를 다시 읽게 한다 —
     * 사용자가 본 것과 다른 것을 적용하지 않기 위해서다.
     *
     * @param revision         검토한 정리안의 판
     * @param editRevision     검토한 편집의 판
     * @param baseTreeVersion  검토할 때 본 학습 구조의 판
     * @param selectedChangeIds 적용할 변경. null이면 "제외하지 않은 전부"가 아니라 <b>잘못된 요청</b>이다 —
     *                          화면이 무엇을 고른지 명시해야 한다
     */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class Apply {
        @NotNull
        private Long revision;
        @NotNull
        private Long editRevision;
        @NotNull
        private Long baseTreeVersion;
        @NotNull
        private List<String> selectedChangeIds;
        /** changeId → 사용자가 고친 제목. 편집 저장과 어긋나면 이 값이 이긴다(마지막으로 본 화면). */
        private Map<String, String> titleOverrides;
    }
}
