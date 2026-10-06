package com.jungwoo.project.memo.plan.help;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.ai.AiProposalMapper;
import com.jungwoo.project.memo.ai.domain.AiProposal;
import com.jungwoo.project.memo.ai.domain.AiProposalItem;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.course.CourseMapper;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.execution.ExecutionItemMapper;
import com.jungwoo.project.memo.execution.ExecutionRecordMapper;
import com.jungwoo.project.memo.execution.domain.ExecutionItem;
import com.jungwoo.project.memo.execution.domain.ExecutionRecord;
import com.jungwoo.project.memo.execution.dto.ExecutionItemResponse;
import com.jungwoo.project.memo.execution.dto.ExecutionRecordResponse;
import com.jungwoo.project.memo.learning.CourseTopicMapper;
import com.jungwoo.project.memo.learning.domain.CourseTopic;
import com.jungwoo.project.memo.plan.PlanItemDetailService;
import com.jungwoo.project.memo.plan.StartSourceResolver;
import com.jungwoo.project.memo.plan.dto.PlanItemDetailResponse;
import com.jungwoo.project.memo.plan.dto.StartSource;
import com.jungwoo.project.memo.plan.provenance.PlanProvenanceCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 실행 항목 하나의 작업 공간. 오늘·계획·일정·프로젝트 어디서 열어도 같은 실행 항목(execution_items 한 행)을 본다 —
 * 화면별 원본이나 진도를 따로 만들지 않는다.
 *
 * <p>모델을 부르지 않는다. 이미 만든 안내·도움·시작 자료·기록을 모아 줄 뿐이다. 안내가 없으면 "없다"와 "만들 수 있는가"를
 * 그대로 말하고, 만드는 것은 사용자가 요청할 때만(POST detail·start-help)이다.
 */
@Service
@RequiredArgsConstructor
public class ExecutionWorkspaceService {

    private final ExecutionItemMapper executionItemMapper;
    private final ExecutionRecordMapper executionRecordMapper;
    private final PlanItemDetailService detailService;
    private final StartHelpService startHelpService;
    private final StartSourceResolver startSourceResolver;
    private final AiProposalMapper aiProposalMapper;
    private final PlanProvenanceCodec provenanceCodec;
    private final CourseMapper courseMapper;
    private final CourseTopicMapper topicMapper;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public ExecutionWorkspaceResponse get(Long userId, Long executionItemId) {
        ExecutionItem item = executionItemMapper.findByIdAndUserId(executionItemId, userId);
        if (item == null) {
            throw new NotFoundException(ErrorCode.EXECUTION_ITEM_NOT_FOUND);
        }
        AiProposalItem origin = detailService.originOf(userId, executionItemId);
        Map<String, Object> payload = origin == null ? Map.of() : readPayload(origin);
        String doneCriteria = str(payload.get("doneCriteria"));

        StartSource start = null;
        PlanItemDetailResponse guidance = null;
        String guidanceState;
        boolean changedSinceDraft = false;
        if (origin == null) {
            guidanceState = "NO_ORIGIN";
        } else {
            AiProposal proposal = aiProposalMapper.findByIdAndUserId(origin.getProposalId(), userId);
            start = startSourceResolver.resolve(userId, provenanceCodec.evidenceFromJson(origin.getEvidenceJson()),
                    proposal == null ? null : provenanceCodec.fromJson(proposal.getPlanProvenanceJson()));
            guidance = detailService.forExecutionItem(userId, executionItemId, false);
            guidanceState = guidance.isAvailable() ? (guidance.isStale() ? "STALE" : "CURRENT")
                    : guidance.isCanGenerate() ? "NOT_YET" : "NO_SOURCE";
            // 확정 뒤 줄이기·남은 분량으로 제목·설명이 바뀌었으면 안내는 확정 당시 항목 기준이다. 숨기지 않고 알린다.
            changedSinceDraft = !Objects.equals(trim(str(payload.get("title"))), trim(item.getTitle()))
                    || !Objects.equals(trim(str(payload.get("description"))), trim(item.getDescription()));
        }

        Course course = item.getCourseId() == null ? null : courseMapper.findByIdAndUserId(item.getCourseId(), userId);
        CourseTopic topic = item.getTopicId() == null ? null : topicMapper.findByIdAndUserId(item.getTopicId(), userId);

        return new ExecutionWorkspaceResponse(
                ExecutionItemResponse.from(item),
                doneCriteria,
                course == null ? null : course.getTitle(),
                topic == null ? null : topic.getTitle(),
                origin == null ? null : origin.getProposalItemId(),
                item.getSourceExecutionItemId(),
                changedSinceDraft,
                start,
                guidanceState,
                guidance,
                startHelpService.latest(userId, executionItemId),
                records(userId, item));
    }

    /** 이 활동의 기록. 부분 수행으로 이어진 앞 조각의 기록도 함께 — 같은 활동이다. */
    private List<ExecutionRecordResponse> records(Long userId, ExecutionItem item) {
        Set<Long> chain = new LinkedHashSet<>();
        ExecutionItem current = item;
        while (current != null && chain.add(current.getExecutionItemId()) && chain.size() <= 20) {
            current = current.getSourceExecutionItemId() == null ? null
                    : executionItemMapper.findByIdAndUserIdIncludingDeleted(current.getSourceExecutionItemId(), userId);
        }
        List<ExecutionRecordResponse> out = new ArrayList<>();
        for (ExecutionRecord r : executionRecordMapper.findByExecutionItemIds(userId, new ArrayList<>(chain))) {
            out.add(ExecutionRecordResponse.builder()
                    .executionRecordId(r.getExecutionRecordId())
                    .executionItemId(r.getExecutionItemId())
                    .outcome(r.getOutcome() == null ? null : r.getOutcome().name())
                    .actualMinutes(r.getActualMinutes())
                    .completionPercent(r.getCompletionPercent())
                    .note(r.getNote())
                    .blockerKind(r.getBlockerKind())
                    .supportLevel(r.getSupportLevel())
                    .stuckStep(r.getStuckStep())
                    .recordedAt(r.getRecordedAt())
                    .build());
        }
        out.sort((a, b) -> b.getRecordedAt() == null || a.getRecordedAt() == null ? 0
                : b.getRecordedAt().compareTo(a.getRecordedAt()));
        return out;
    }

    private Map<String, Object> readPayload(AiProposalItem item) {
        String json = item.getEditedPayload() != null ? item.getEditedPayload() : item.getOriginalPayload();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * @param guidanceState CURRENT(지금 항목·원문 기준 안내) · STALE(예전 안내) · NOT_YET(아직 안 만듦, 만들 수 있음) ·
     *                      NO_SOURCE(인용한 자료 구간이 없어 단계 안내를 만들지 않음) · NO_ORIGIN(직접 만든 항목)
     */
    public record ExecutionWorkspaceResponse(
            ExecutionItemResponse item,
            String doneCriteria,
            String courseTitle,
            String topicTitle,
            Long proposalItemId,
            Long leftoverOfId,
            boolean changedSinceDraft,
            StartSource startSource,
            String guidanceState,
            PlanItemDetailResponse guidance,
            StartHelpResponse startHelp,
            List<ExecutionRecordResponse> records
    ) {
    }
}
