package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.ai.domain.FactKind;
import com.jungwoo.project.memo.ai.photo.AiMessagePhotoMapper;
import com.jungwoo.project.memo.ai.state.ProjectStateService;
import com.jungwoo.project.memo.plan.selection.PlanMaterialSelector;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 계획이 먼저 읽을 사진 본문(C). 막힌·도움받아 해결한 단원(확인된 기억)에 연결된 상담 사진 구간을 단원마다 최대 2장 고른다.
 *
 * <p>우선순위: ① 그 기억을 말할 때 보던 사진(기억의 근거 메시지 이하에서 같은 대화에 붙은 사진, 가까운 것 먼저) ② 사용자가 단원을
 * 확인한 사진(최신순) ③ 서버가 단원을 추정한 사진(최신순). 고른 구간은 접힌 목록에서도 줄로 보이고, 선택 모델이 고르지 않으면
 * 서버가 보태며, 입력 예산을 줄일 때 맨 마지막에 뺀다.
 */
@Slf4j
public final class FocusPhotos {

    static final int PER_TOPIC = 2;
    static final int MAX_TOTAL = 4;
    static final String REASON = "막힌·도움받아 해결한 단원의 사진 본문";

    private FocusPhotos() {
    }

    /** 초점 사진 구간에 focus 표시를 한 카탈로그. 사진이 없거나 초점 단원이 없으면 그대로. */
    static PlanMaterialContextService.CourseCatalog mark(PlanMaterialContextService.CourseCatalog catalog,
                                                         ProjectStateService.State state, AiMessagePhotoMapper mapper,
                                                         Long userId) {
        if (catalog == null || state == null || catalog.sections().stream().noneMatch(PlanMaterialContextService.SectionLine::photo)) {
            return catalog;
        }
        Set<Long> chosen = new LinkedHashSet<>();
        Set<Long> seenTopics = new HashSet<>();
        for (ProjectStateService.Fact f : state.facts()) {
            if (f.topicId() == null || f.inferred() || (f.kind() != FactKind.DIFFICULTY && f.kind() != FactKind.RESOLVED)
                    || !seenTopics.add(f.topicId())) {
                continue;
            }
            List<PlanMaterialContextService.SectionLine> photos = catalog.sections().stream()
                    .filter(PlanMaterialContextService.SectionLine::photo)
                    .filter(s -> s.topicIds().contains(f.topicId())).toList();
            if (photos.isEmpty()) {
                continue;
            }
            List<Long> seenWhenSaid = seenWhenSaid(f, mapper, userId);
            List<PlanMaterialContextService.SectionLine> ordered = new ArrayList<>(photos);
            ordered.sort(Comparator
                    .comparingInt((PlanMaterialContextService.SectionLine s) -> {
                        int i = seenWhenSaid.indexOf(s.material().getMaterialId());
                        return i < 0 ? Integer.MAX_VALUE : i;
                    })
                    .thenComparing(s -> "GUESSED".equals(s.photoLink()) ? 1 : 0)
                    .thenComparing(s -> s.section().getCreatedAt(), Comparator.nullsLast(Comparator.<LocalDateTime>reverseOrder())));
            ordered.stream().limit(PER_TOPIC).forEach(s -> chosen.add(s.section().getSectionId()));
            if (chosen.size() >= MAX_TOTAL) {
                break;
            }
        }
        if (chosen.isEmpty()) {
            return catalog;
        }
        Set<Long> keep = chosen.stream().limit(MAX_TOTAL).collect(java.util.stream.Collectors.toSet());
        return new PlanMaterialContextService.CourseCatalog(catalog.courseId(), catalog.courseTitle(), catalog.topics(),
                catalog.sections().stream().map(s -> keep.contains(s.section().getSectionId()) ? s.withFocus() : s).toList(),
                catalog.excluded(), catalog.open(), catalog.completed(), catalog.unconfirmed(), catalog.pending(),
                catalog.materials(), catalog.totalTopics(), catalog.proposedBySection());
    }

    private static List<Long> seenWhenSaid(ProjectStateService.Fact f, AiMessagePhotoMapper mapper, Long userId) {
        if (mapper == null || f.sourceMessageId() == null) {
            return List.of();
        }
        try {
            Long conversationId = mapper.findConversationOfMessage(f.sourceMessageId(), userId);
            return conversationId == null ? List.of() : mapper.findPhotoIdsUpTo(conversationId, userId, f.sourceMessageId());
        } catch (Exception e) {
            log.warn("기억의 근거 메시지에서 사진을 찾지 못함(최신순으로): contextId={}", f.contextId());
            return List.of();
        }
    }

    /** 초점 사진 구간 id 전부. */
    static Set<Long> sectionIds(List<PlanMaterialContextService.CourseCatalog> catalogs) {
        Set<Long> out = new LinkedHashSet<>();
        for (PlanMaterialContextService.CourseCatalog c : catalogs) {
            c.sections().stream().filter(PlanMaterialContextService.SectionLine::focus)
                    .forEach(s -> out.add(s.section().getSectionId()));
        }
        return out;
    }

    /** 선택 모델이 고르지 않은 초점 사진 구간을 보탠다(후보 목록의 핸들로). */
    static PlanMaterialSelector.Result withFocusSections(PlanMaterialSelector.Result selection,
                                                         List<PlanMaterialContextService.CourseCatalog> catalogs) {
        Set<Long> focus = sectionIds(catalogs);
        if (selection == null || focus.isEmpty() || selection.byHandle() == null) {
            return selection;
        }
        Set<Long> picked = new HashSet<>();
        selection.sections().forEach(s -> picked.add(s.line().section().getSectionId()));
        List<PlanMaterialSelector.SelectedSection> sections = new ArrayList<>(selection.sections());
        for (Map.Entry<String, PlanMaterialSelector.SelectedSection> e : selection.byHandle().entrySet()) {
            Long id = e.getValue().line().section().getSectionId();
            if (focus.contains(id) && picked.add(id)) {
                sections.add(new PlanMaterialSelector.SelectedSection(e.getKey(), e.getValue().line(), e.getValue().catalog(),
                        REASON));
            }
        }
        if (sections.size() == selection.sections().size()) {
            return selection;
        }
        return new PlanMaterialSelector.Result(selection.status() == PlanMaterialSelector.Status.NO_CANDIDATES
                ? selection.status() : PlanMaterialSelector.Status.SELECTED, selection.mode(), sections, selection.topics(),
                selection.insufficientEvidence(), selection.note(), selection.calls(), selection.expanded(),
                selection.candidateTotal(), selection.candidateShown(), selection.unreviewed(), selection.unknownIds(),
                selection.estimatedInputTokens(), selection.overLimit(), selection.sectionHandles(), selection.byHandle(),
                selection.exposure(), selection.traces());
    }

    /** 초점 사진 구간이 있으면 true(같은 구간이 다른 이유로 골렸어도). */
    static boolean isFocus(Long sectionId, List<PlanMaterialContextService.CourseCatalog> catalogs) {
        return sectionId != null && catalogs.stream().anyMatch(c -> c.sections().stream()
                .anyMatch(s -> s.focus() && Objects.equals(s.section().getSectionId(), sectionId)));
    }
}
