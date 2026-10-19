package com.jungwoo.project.memo.learning.structure;

import com.jungwoo.project.memo.learning.domain.TopicChangeProposalStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 자료 정리 변경안 한 건. 화면은 summary로 한 줄 요약("기존 내용에 자료 3곳 연결 · 새 항목 2개 제안")을
 * 그리고, ops·topicTitles·sections로 접힌 세부 diff를 그린다. 이동·병합·분할이 있으면 summary.structural이
 * true라 접지 않고 보여준다.
 *
 * <p>currentTreeVersion ≠ baseTreeVersion이면 이 변경안은 지금 트리와 어긋난다 — 적용하면 409다.
 */
@Getter
@Builder
public class TopicChangeProposalResponse {

    private Long proposalId;
    private Long courseId;
    private Long materialId;
    private String materialFilename;
    private TopicChangeProposalStatus status;
    private Long baseTreeVersion;
    private Long currentTreeVersion;
    private boolean stale;
    private TopicChangeOpsValidator.Summary summary;
    private List<TopicChangeOp> ops;
    private Map<Long, String> topicTitles;
    private List<SectionBrief> sections;
    private LocalDateTime createdAt;
    private LocalDateTime resolvedAt;

    public record SectionBrief(Long sectionId, String title, String locator, List<String> roles, String taskText,
                               String excerpt) {
    }
}
