package com.jungwoo.project.memo.ai.change;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.plan.selection.PlanRequestContext;
import com.jungwoo.project.memo.plan.selection.PlanScenarioSnapshot;
import com.jungwoo.project.memo.scheduling.scenario.DraftScenarioSource;
import com.jungwoo.project.memo.scheduling.scenario.Scenario;
import com.jungwoo.project.memo.scheduling.scenario.ScenarioProjection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 계획 초안과 변경 묶음을 잇는다 — 초안 생성은 원본에 묶음의 선택 항목을 겹친 가정 상태로, 확정은 그 묶음 항목을 같은 트랜잭션에서
 * 먼저 적용한 뒤 계획을 저장한다. 근무를 먼저 실제로 빼고 계획을 만드는 흐름을 만들지 않는다.
 *
 * <p>초안에는 묶음의 불변 스냅샷(리비전·선택·op 내용)을 남긴다. 미리보기·확정은 지금 묶음을 다시 읽어 조용히 다른 상태로 계산하지 않는다 —
 * 묶음이 바뀌었으면(선택 해제·다시 검토·다른 선택으로 적용) 그 초안은 재계산 대상이고 확정을 막는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanScenarioService implements DraftScenarioSource {

    private final ChangeBundleMapper mapper;
    private final ChangeRegistry registry;
    private final ChangeBundleService bundles;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** 초안 생성에 쓸 가정 상태와 그 스냅샷. */
    public record Resolved(Scenario scenario, PlanScenarioSnapshot snapshot) {
    }

    /**
     * 그 대화에서 아직 적용하지 않은 가장 최근 묶음의 선택 항목(시간·마감 효과가 있는 것). 없으면 null — 원본으로 만든다.
     */
    @Transactional(readOnly = true)
    public Resolved forConversation(Long userId, Long conversationId) {
        if (conversationId == null) {
            return null;
        }
        return mapper.findByConversation(conversationId, userId).stream()
                .filter(b -> ChangeBundleRows.PROPOSED.equals(b.getStatus()))
                .max(Comparator.comparing(ChangeBundleRows.Bundle::getBundleId))
                .map(b -> forBundle(userId, b.getBundleId()))
                .orElse(null);
    }

    /** 그 묶음의 지금 선택으로. 이미 적용됐거나 닫혔으면 null(원본이 이미 그 상태이거나 기댈 것이 없다). */
    @Transactional(readOnly = true)
    public Resolved forBundle(Long userId, Long bundleId) {
        ChangeBundleRows.Bundle bundle = mapper.findBundle(bundleId, userId);
        if (bundle == null || !ChangeBundleRows.PROPOSED.equals(bundle.getStatus())) {
            return null;
        }
        List<PlanScenarioSnapshot.Op> ops = new ArrayList<>();
        List<Long> itemIds = new ArrayList<>();
        List<String> summaries = new ArrayList<>();
        for (ChangeBundleRows.Item item : mapper.findItems(bundleId, userId)) {
            if (!Boolean.TRUE.equals(item.getSelected()) || !ChangeCheck.OK.equals(item.getCheckCode())) {
                continue;
            }
            itemIds.add(item.getItemId());
            ops.add(new PlanScenarioSnapshot.Op(item.getOpType(), item.getOpJson()));
            summaries.add(item.getSummary());
        }
        if (ops.isEmpty()) {
            return null;
        }
        Scenario scenario = bundles.scenario(userId, bundleId, itemIds);
        PlanScenarioSnapshot snapshot = new PlanScenarioSnapshot(bundleId, bundle.getRevision(), itemIds,
                scenario.ref().fingerprint(), ops, summaries);
        return new Resolved(scenario, snapshot);
    }

    /** 초안의 스냅샷으로 가정 상태를 다시 만든다(미리보기). 지금 묶음은 읽지 않는다. */
    @Override
    public Scenario scenarioFor(Long userId, String planRequestJson) {
        PlanScenarioSnapshot snapshot = snapshotOf(planRequestJson);
        if (snapshot == null) {
            return Scenario.ORIGINAL;
        }
        // 확정으로 이미 적용된 묶음이면 원본이 그 상태다 — 다시 겹치지 않는다.
        ChangeBundleRows.Bundle bundle = mapper.findBundle(snapshot.bundleId(), userId);
        if (bundle == null || !ChangeBundleRows.PROPOSED.equals(bundle.getStatus())) {
            return Scenario.ORIGINAL;
        }
        List<ScenarioProjection> projections = new ArrayList<>();
        for (PlanScenarioSnapshot.Op op : snapshot.ops()) {
            if (registry.read(op.type(), op.json()) instanceof ScenarioProjection p) {
                projections.add(p);
            }
        }
        return Scenario.of(projections, new Scenario.ScenarioRef(snapshot.bundleId(), snapshot.revision(),
                snapshot.itemIds(), snapshot.fingerprint()));
    }

    /**
     * 계획 확정 직전(같은 트랜잭션): 초안이 기댄 변경을 먼저 적용한다. 묶음이 초안을 만든 뒤 바뀌었으면 확정을 막는다(재계산 필요).
     * 이미 같은 선택으로 적용됐으면(묶음만 먼저 적용) 그대로 인정한다.
     *
     * @throws ConflictException PLAN_SCENARIO_CHANGED — 묶음 리비전·선택이 달라졌거나 닫혔다
     */
    @Transactional
    public void applyForConfirm(Long userId, String planRequestJson, Long proposalId) {
        PlanScenarioSnapshot snapshot = snapshotOf(planRequestJson);
        if (snapshot == null) {
            return;
        }
        ChangeBundleRows.Bundle bundle = mapper.findBundleForUpdate(snapshot.bundleId(), userId);
        if (bundle == null) {
            throw changed(snapshot, "묶음을 찾을 수 없어요");
        }
        if (ChangeBundleRows.APPLIED.equals(bundle.getStatus())) {
            Set<Long> applied = new HashSet<>();
            for (ChangeBundleRows.Item item : mapper.findItemsForUpdate(snapshot.bundleId(), userId)) {
                if (ChangeBundleRows.APPLIED.equals(item.getStatus())) {
                    applied.add(item.getItemId());
                }
            }
            if (applied.equals(new HashSet<>(snapshot.itemIds()))) {
                return; // 묶음을 먼저 적용했다 — 초안이 기댄 바로 그 변경이다
            }
            throw changed(snapshot, "초안을 만든 뒤 다른 선택으로 적용됐어요");
        }
        if (!ChangeBundleRows.PROPOSED.equals(bundle.getStatus()) || bundle.getRevision() != snapshot.revision()) {
            throw changed(snapshot, "초안을 만든 뒤 변경 선택이 바뀌었어요");
        }
        bundles.apply(userId, snapshot.bundleId(), new ChangeBundleService.ApplyCommand(snapshot.revision(),
                snapshot.itemIds(), "plan-confirm-" + proposalId));
        log.info("계획 확정과 함께 변경 묶음 적용: userId={}, bundleId={}, proposalId={}", userId, snapshot.bundleId(), proposalId);
    }

    public PlanScenarioSnapshot snapshotOf(String planRequestJson) {
        if (planRequestJson == null || planRequestJson.isBlank()) {
            return null;
        }
        try {
            PlanRequestContext context = json.readValue(planRequestJson, PlanRequestContext.class);
            return context.scenario();
        } catch (Exception e) {
            return null;
        }
    }

    private static ConflictException changed(PlanScenarioSnapshot snapshot, String why) {
        return new ConflictException(ErrorCode.PLAN_SCENARIO_CHANGED, Map.of("bundleId", snapshot.bundleId(),
                "reason", why, "changes", Objects.requireNonNullElse(snapshot.summaries(), List.of())));
    }
}
