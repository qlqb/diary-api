package com.jungwoo.project.memo.ai.state;

import com.jungwoo.project.memo.common.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 프로젝트 사실 카드 — "이 프로젝트에서 기억하는 것". 상담·계획이 받는 것과 같은 조회({@link ProjectStateService})를 보여 준다.
 *
 * <p>읽기 전용이다. 고치기·지우기·확인은 기억 API(/api/contexts/{id})로 한다 — 대체 이력과 초안 "오래됨" 표시가 한곳에서
 * 일어나게. 입력 폼은 없다: 기억은 상담이 채우고, 카드는 확인·수정용이다.
 */
@Slf4j
@RestController
@RequestMapping("/api/courses/{courseId}/study-state")
@RequiredArgsConstructor
public class StudyStateController {

    private final ProjectStateService projectStateService;

    /**
     * @param sourceLabel 출처(사용자가 말함·사용자가 고침·자기평가·AI 추정 — 확인 전). 서버가 붙인 문구
     */
    public record FactView(Long contextId, String kind, String label, String text, Long topicId, String topicTitle,
                           Integer topicTocSeq, String evidenceType, String sourceType, String sourceLabel, String help,
                           LocalDateTime saidAt) {
    }

    /** 고치기에서 고를 수 있는 단원(그 과목의 살아 있는 단원). 목차 순번으로 같은 제목을 가른다. */
    public record TopicOption(Long topicId, String title, Integer sourceTocSeq) {
    }

    public record StudyStateResponse(Long courseId, String textbook, List<FactView> facts,
                                     List<ProjectStateService.ClassLine> classProgress,
                                     List<ProjectStateService.Exclusion> exclusions, List<TopicOption> topics) {
    }

    @GetMapping
    public ResponseEntity<StudyStateResponse> get(@AuthenticationPrincipal UserPrincipal principal,
                                                  @PathVariable Long courseId) {
        ProjectStateService.State state = projectStateService.load(principal.getUserId(), courseId);
        List<FactView> facts = state.facts().stream().map(f -> new FactView(f.contextId(), f.kind().name(), f.label(),
                f.text(), f.topicId(), f.topicTitle(), f.topicTocSeq(),
                f.evidenceType() == null ? null : f.evidenceType().name(),
                f.sourceType() == null ? null : f.sourceType().name(), ProjectStateService.sourceLabel(f), f.help(),
                f.saidAt())).toList();
        List<TopicOption> topics = projectStateService.topics(principal.getUserId(), courseId).stream()
                .map(t -> new TopicOption(t.getTopicId(), t.getTitle(), t.getSourceTocSeq())).toList();
        return ResponseEntity.ok(new StudyStateResponse(state.courseId(), state.textbookLine(), facts,
                state.classProgress(), state.exclusions(), topics));
    }
}
