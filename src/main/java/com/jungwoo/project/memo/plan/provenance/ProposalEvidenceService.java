package com.jungwoo.project.memo.plan.provenance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.ai.AiProposalItemMapper;
import com.jungwoo.project.memo.ai.domain.AiProposalItem;
import com.jungwoo.project.memo.execution.domain.PlacementType;
import com.jungwoo.project.memo.scheduling.SchedulePreviewMapper;
import com.jungwoo.project.memo.scheduling.domain.AiProposalSchedulePreview;
import com.jungwoo.project.memo.scheduling.dto.PlacedItemDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 사용자가 항목을 고쳤을 때 그 항목의 근거 상태를 갱신한다.
 *
 * <p>★ 근거를 지우거나 덮어쓰지 않는다. 상태와 이유만 붙인다 — 고치기 전 근거가 남아
 * 있어야 "무엇을 고쳐서 이렇게 됐는지"를 읽을 수 있다(handoff §6).
 *
 * <p>★ 고친 뒤 모델을 다시 부르지 않는다. 그래서 "AI가 다시 검토했다"로 읽힐 문구도
 * 만들지 않는다. 여기서 하는 일은 기존 근거에 정직한 꼬리표를 다는 것뿐이다.
 *
 * <p>판별 범위는 <b>지금 적용 요청이 실제로 비교하는 필드</b>다. 의미가 바뀌었는지를 알아
 * 보려고 새 LLM 호출을 붙이지 않는다. 애매하면 보수적으로 재검토 대상으로 올린다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProposalEvidenceService {

    private final AiProposalItemMapper aiProposalItemMapper;
    private final PlanProvenanceCodec codec;
    private final SchedulePreviewMapper schedulePreviewMapper;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    /**
     * 이번 적용에서 "서버가 미리 정한 시각"이 무엇이었는지.
     *
     * <p>확정 요청은 배치 미리보기가 정한 시각을 그대로 실어 보낸다. 그것까지 사용자가 고친
     * 것으로 세면 <b>모든 항목</b>에 "다시 볼 근거"가 붙어 표식이 뜻을 잃는다 — 실제로
     * 그렇게 돼서 확정 직후 네 항목 전부에 경고가 붙었다.
     *
     * <p>적용 한 번에 한 번만 조회한다.
     */
    public PlacementBaseline placementBaseline(Long proposalId, Long userId) {
        Map<Long, PlacedItemDto> byItem = new HashMap<>();
        AiProposalSchedulePreview preview = schedulePreviewMapper.findByProposalIdAndUserId(proposalId, userId);
        if (preview != null && preview.getPlacedItems() != null) {
            try {
                for (PlacedItemDto placed : objectMapper.readValue(preview.getPlacedItems(),
                        new TypeReference<List<PlacedItemDto>>() {})) {
                    if (placed.getProposalItemId() != null) {
                        byItem.put(placed.getProposalItemId(), placed);
                    }
                }
            } catch (Exception e) {
                // 못 읽으면 서버가 정한 시각을 모른다는 뜻이다. 그때는 보수적으로
                // "사용자가 바꿨다"로 본다 — 조용히 넘기면 진짜 수정까지 놓친다.
                log.warn("배치 미리보기를 읽지 못했다 — 시각 변경을 사용자 수정으로 본다. proposalId={}",
                        proposalId);
            }
        }
        return new PlacementBaseline(byItem);
    }

    /** 서버가 미리 정해 둔 시각. 확정 요청의 시각이 이것과 같으면 사용자가 고친 것이 아니다. */
    public record PlacementBaseline(Map<Long, PlacedItemDto> byProposalItemId) {

        public boolean isServerPlacement(Long proposalItemId, PlacementType placementType,
                                         LocalDate scheduledDate, LocalDateTime start, LocalDateTime end) {
            PlacedItemDto placed = byProposalItemId.get(proposalItemId);
            if (placed == null) {
                return false;
            }
            return Objects.equals(placed.getPlacementType(), placementType)
                    && Objects.equals(placed.getScheduledDate(), scheduledDate)
                    && Objects.equals(placed.getScheduledStartAt(), start)
                    && Objects.equals(placed.getScheduledEndAt(), end);
        }
    }

    /**
     * @param scheduleChanged 날짜·시각·배치 방식이 바뀌었다. 그때의 배치 계산은 이 시각에
     *                        대한 것이 아니므로 재검토 대상이다
     * @param contentChanged  학습 내용·분량이 바뀌었다. 근거는 수정 전 제안의 것이 된다
     */
    public void markEdited(AiProposalItem item, boolean scheduleChanged, boolean contentChanged) {
        if (item == null || item.getEvidenceJson() == null) {
            // 근거 없이 만들어진 제안(레거시·비계획 경로)이다. 만들어 붙이지 않는다.
            return;
        }
        PlanItemEvidence evidence = codec.evidenceFromJson(item.getEvidenceJson());
        if (evidence == null) {
            return;
        }

        List<String> reasons = new ArrayList<>();
        if (scheduleChanged) {
            reasons.add("확정할 때 날짜·시각을 바꿨어요. 아래 배치 근거는 바꾸기 전 상태예요.");
        }
        if (contentChanged) {
            reasons.add("확정할 때 내용이나 분량을 바꿨어요. 아래는 수정 전 제안의 근거예요.");
        }
        if (reasons.isEmpty()) {
            // 우선순위처럼 근거와 무관한 값만 바뀐 경우. 상태를 건드리지 않는다.
            return;
        }

        EvidenceStatus status = scheduleChanged ? EvidenceStatus.NEEDS_REVIEW : EvidenceStatus.EDITED_BY_USER;
        String json = codec.toJson(evidence.withStatus(status, reasons));
        if (json == null) {
            return;
        }
        int updated = aiProposalItemMapper.updateEvidenceJson(item.getProposalItemId(), item.getUserId(), json);
        if (updated != 1) {
            // 적용 자체를 되돌리지 않는다 — 근거 꼬리표가 붙지 않은 것이 적용 실패보다 가볍다.
            log.warn("항목 근거 상태 갱신 행 수 불일치: proposalItemId={}, 실제={}",
                    item.getProposalItemId(), updated);
        }
    }
}
