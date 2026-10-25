package com.jungwoo.project.memo.ai.change;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.scheduling.scenario.Scenario;
import com.jungwoo.project.memo.scheduling.scenario.ScenarioProjection;
import com.jungwoo.project.memo.scheduling.service.ScheduleBasisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 변경 묶음의 저장·검토·적용(공통 변경안 처리부). 도메인 규칙은 모르고 {@link ChangeRegistry}의 모듈에 맡긴다.
 *
 * <p><b>적용 전에는 가정이다.</b> 묶음을 만들고 고르고 다시 확인하는 동안 원본(일정·실행·과제)은 바뀌지 않는다. 원본은 {@link #apply}
 * 한 트랜잭션에서만 바뀐다: 묶음 행 잠금 → 리비전·멱등 키 확인 → 모듈별 대상 잠금(약속 → 실행 → 반복 → 과제) → 영향 범위 실제 일정
 * 지문 비교(잠금 조회) → 항목별 최신 재확인 → 기존 서비스로 적용 → 결과 저장. 어느 단계든 실패하면 전부 되돌아간다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChangeBundleService {

    private final ChangeBundleMapper mapper;
    private final ChangeRegistry registry;
    private final ScheduleBasisService basis;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    /**
     * 확인을 마친 변경(도구 단계의 턴 작업 공간)을 묶음으로 저장한다. 상담 턴 완료 트랜잭션 안에서 부른다.
     *
     * @param dependsOn 같은 목록 안의 앞 항목 번호(0부터)
     */
    public record Staged(ChangeOp op, List<Integer> dependsOn, boolean selected, String origin) {
    }

    public ChangeBundleView create(Long userId, Long conversationId, Long sourceMessageId, List<Staged> staged) {
        if (staged == null || staged.isEmpty()) {
            return null;
        }
        Set<String> targets = new HashSet<>();
        for (Staged s : staged) {
            if (!targets.add(s.op().targetKey())) {
                // 한 묶음에서 같은 대상의 변경은 하나만 — 순서·version 승계 문제를 만들지 않는다(턴 작업 공간이 먼저 막는다)
                throw new IllegalArgumentException("같은 대상의 변경이 둘이다: " + s.op().targetKey());
            }
        }
        LocalDate[] range = range(staged.stream().map(Staged::op).toList());
        ChangeBundleRows.Bundle bundle = ChangeBundleRows.Bundle.builder()
                .userId(userId).conversationId(conversationId).sourceMessageId(sourceMessageId)
                .rangeStart(range[0]).rangeEnd(range[1])
                .basisFingerprint(range[0] == null ? null : basis.fingerprint(userId, range[0], range[1]))
                .build();
        mapper.insertBundle(bundle);
        for (int i = 0; i < staged.size(); i++) {
            Staged s = staged.get(i);
            mapper.insertItem(ChangeBundleRows.Item.builder()
                    .bundleId(bundle.getBundleId()).userId(userId).seq(i + 1)
                    .opType(s.op().type()).targetKey(s.op().targetKey()).opJson(registry.write(s.op()))
                    .summary(cut(s.op().summary(), 300))
                    .dependsOn(s.dependsOn() == null || s.dependsOn().isEmpty() ? null
                            : s.dependsOn().stream().map(d -> String.valueOf(d + 1)).collect(Collectors.joining(",")))
                    .origin(s.origin() == null ? "PROPOSED" : s.origin())
                    .selected(s.selected()).checkCode(ChangeCheck.OK)
                    .build());
        }
        log.info("변경 묶음 저장: userId={}, bundleId={}, 항목={}", userId, bundle.getBundleId(), staged.size());
        return view(userId, bundle.getBundleId());
    }

    @Transactional(readOnly = true)
    public ChangeBundleView view(Long userId, Long bundleId) {
        ChangeBundleRows.Bundle bundle = mapper.findBundle(bundleId, userId);
        if (bundle == null) {
            throw new NotFoundException(ErrorCode.CHANGE_BUNDLE_NOT_FOUND);
        }
        return toView(bundle, mapper.findItems(bundleId, userId));
    }

    /** 재접속 복원: 그 대화의 묶음 전부(상태 무관). */
    @Transactional(readOnly = true)
    public List<ChangeBundleView> listByConversation(Long userId, Long conversationId) {
        return mapper.findByConversation(conversationId, userId).stream()
                .map(b -> toView(b, mapper.findItems(b.getBundleId(), userId))).toList();
    }

    @Transactional(readOnly = true)
    public List<ChangeBundleView> findBySourceMessage(Long userId, Long sourceMessageId) {
        return mapper.findBySourceMessage(sourceMessageId, userId).stream()
                .map(b -> toView(b, mapper.findItems(b.getBundleId(), userId))).toList();
    }

    /** 선택을 바꾼다. 리비전이 오른다 — 그 묶음에 기댄 초안은 리비전이 달라져 재계산 대상이 된다. */
    @Transactional
    public ChangeBundleView select(Long userId, Long bundleId, int expectedRevision, Collection<Long> selectedItemIds) {
        ChangeBundleRows.Bundle bundle = lockOpen(userId, bundleId, expectedRevision);
        Set<Long> wanted = selectedItemIds == null ? Set.of() : new HashSet<>(selectedItemIds);
        List<ChangeBundleRows.Item> items = mapper.findItems(bundleId, userId);
        Set<Long> known = items.stream().map(ChangeBundleRows.Item::getItemId).collect(Collectors.toSet());
        if (!known.containsAll(wanted)) {
            throw new NotFoundException(ErrorCode.CHANGE_BUNDLE_NOT_FOUND); // 다른 묶음의 항목 id
        }
        for (ChangeBundleRows.Item item : items) {
            boolean next = wanted.contains(item.getItemId());
            if (!Objects.equals(item.getSelected(), next)) {
                mapper.updateSelection(item.getItemId(), bundleId, next);
            }
        }
        bump(userId, bundle, null, new LocalDate[]{null, null});
        return view(userId, bundleId);
    }

    /**
     * 다시 확인한다(STALE 뒤 "다시 검토"). 항목마다 모듈이 지금 상태로 확인하고 검토 기준(version·해시·범위 지문)을 새로 잡는다.
     * 원본은 바꾸지 않는다.
     */
    @Transactional
    public ChangeBundleView refresh(Long userId, Long bundleId, int expectedRevision) {
        ChangeBundleRows.Bundle bundle = lockOpen(userId, bundleId, expectedRevision);
        List<ChangeBundleRows.Item> items = mapper.findItems(bundleId, userId);
        List<ChangeOp> ops = new ArrayList<>();
        for (ChangeBundleRows.Item item : items) {
            if (!ChangeBundleRows.PROPOSED.equals(item.getStatus())) {
                continue;
            }
            ChangeOp op = registry.read(item.getOpType(), item.getOpJson());
            ChangeCheck check = safeCheck(userId, op);
            mapper.updateItemCheck(item.getItemId(), bundleId, registry.write(check.op()), check.code(),
                    cut(check.message(), 300));
            ops.add(check.op());
        }
        LocalDate[] range = range(ops);
        bump(userId, bundle, range[0] == null ? null : basis.fingerprint(userId, range[0], range[1]), range);
        return view(userId, bundleId);
    }

    @Transactional
    public ChangeBundleView dismiss(Long userId, Long bundleId) {
        ChangeBundleRows.Bundle bundle = mapper.findBundleForUpdate(bundleId, userId);
        if (bundle == null) {
            throw new NotFoundException(ErrorCode.CHANGE_BUNDLE_NOT_FOUND);
        }
        if (!ChangeBundleRows.PROPOSED.equals(bundle.getStatus())) {
            throw new ConflictException(ErrorCode.CHANGE_BUNDLE_ALREADY_RESOLVED);
        }
        for (ChangeBundleRows.Item item : mapper.findItems(bundleId, userId)) {
            mapper.resolveItem(item.getItemId(), bundleId, ChangeBundleRows.DISMISSED, null);
        }
        mapper.resolveBundle(bundleId, userId, ChangeBundleRows.DISMISSED, null, null, null);
        return view(userId, bundleId);
    }

    /**
     * @param expectedRevision 사용자가 검토한 리비전
     * @param selectedItemIds  적용할 항목(나머지는 DISMISSED)
     * @param idempotencyKey   화면이 적용 시도마다 만든 키. 같은 키로 다시 오면 저장된 결과를 돌려준다
     */
    public record ApplyCommand(int expectedRevision, List<Long> selectedItemIds, String idempotencyKey) {
    }

    /** 적용 직후의 묶음과, 함께 묶어 적용할 일(계획 확정 등)이 쓸 수 있는 항목 결과. */
    @Transactional
    public ChangeBundleView apply(Long userId, Long bundleId, ApplyCommand command) {
        String requestHash = hash(bundleId + "|" + command.expectedRevision() + "|"
                + (command.selectedItemIds() == null ? "" : command.selectedItemIds().stream().sorted()
                .map(String::valueOf).collect(Collectors.joining(","))));
        // 묶음 행을 먼저 잠근다. 같은 키의 동시 요청은 여기서 줄 서고, 앞선 요청이 끝난 뒤 최신 커밋(잠금 조회)을 본다.
        ChangeBundleRows.Bundle bundle = mapper.findBundleForUpdate(bundleId, userId);
        if (bundle == null) {
            throw new NotFoundException(ErrorCode.CHANGE_BUNDLE_NOT_FOUND);
        }
        if (!ChangeBundleRows.PROPOSED.equals(bundle.getStatus())) {
            if (Objects.equals(bundle.getApplyIdempotencyKey(), command.idempotencyKey())
                    && Objects.equals(bundle.getApplyRequestHash(), requestHash)) {
                // 응답 유실 뒤 재시도·중복 클릭 — 이미 적용한 결과를 잠근 최신 행으로 돌려준다
                return toView(bundle, mapper.findItemsForUpdate(bundleId, userId));
            }
            if (Objects.equals(bundle.getApplyIdempotencyKey(), command.idempotencyKey())) {
                throw new ConflictException(ErrorCode.CHANGE_BUNDLE_IDEMPOTENCY_REUSED);
            }
            throw new ConflictException(ErrorCode.CHANGE_BUNDLE_ALREADY_RESOLVED);
        }
        if (command.idempotencyKey() != null) {
            ChangeBundleRows.Bundle byKey = mapper.findByApplyKey(userId, command.idempotencyKey());
            if (byKey != null && !Objects.equals(byKey.getBundleId(), bundleId)) {
                throw new ConflictException(ErrorCode.CHANGE_BUNDLE_IDEMPOTENCY_REUSED); // 다른 묶음 적용에 쓴 키
            }
        }
        if (bundle.getRevision() != command.expectedRevision()) {
            throw new ConflictException(ErrorCode.CHANGE_BUNDLE_REVISION_CHANGED,
                    Map.of("currentRevision", bundle.getRevision()));
        }
        List<ChangeBundleRows.Item> items = mapper.findItems(bundleId, userId);
        Set<Long> selected = new HashSet<>(command.selectedItemIds() == null ? List.of() : command.selectedItemIds());
        Map<Integer, ChangeBundleRows.Item> bySeq = new LinkedHashMap<>();
        items.forEach(i -> bySeq.put(i.getSeq(), i));
        if (!items.stream().map(ChangeBundleRows.Item::getItemId).collect(Collectors.toSet()).containsAll(selected)) {
            throw new NotFoundException(ErrorCode.CHANGE_BUNDLE_NOT_FOUND);
        }
        List<Map<String, Object>> problems = new ArrayList<>();
        List<ChangeBundleRows.Item> toApply = new ArrayList<>();
        for (ChangeBundleRows.Item item : items) {
            if (!selected.contains(item.getItemId())) {
                continue;
            }
            toApply.add(item);
            if (!ChangeCheck.OK.equals(item.getCheckCode())) {
                problems.add(problem(item, item.getCheckCode(), item.getCheckMessage()));
            }
            for (Integer dep : depends(item)) {
                ChangeBundleRows.Item d = bySeq.get(dep);
                if (d == null || !selected.contains(d.getItemId())) {
                    throw new ConflictException(ErrorCode.CHANGE_BUNDLE_DEPENDENCY,
                            Map.of("itemId", item.getItemId(), "needsSeq", dep));
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new ConflictException(ErrorCode.CHANGE_BUNDLE_ITEM_NOT_APPLICABLE, problems);
        }

        // 잠금 순서: 범위(약속 → 시각 고정 실행 → 반복) → 모듈 대상(약속 → 실행 → 반복 → 과제). 계획 확정과 같은 도메인 순서다.
        // 대상의 날짜는 범위 안이라(affectedDates) 범위 잠금이 대상 행을 먼저 잡는다 — 묶음끼리 순서가 뒤집히지 않는다.
        List<ChangeOp> ops = toApply.stream().map(i -> registry.read(i.getOpType(), i.getOpJson())).toList();
        String lockedNow = bundle.getRangeStart() == null ? null
                : basis.lockedFingerprint(userId, bundle.getRangeStart(), bundle.getRangeEnd());
        // 범위가 검토 뒤 바뀌었으면 대상 잠금을 더 잡기 전에 거절한다 — 대상이 범위 밖으로 옮겨진 경우 잠금 순서가 뒤집히지 않게.
        if (bundle.getRangeStart() != null && !Objects.equals(lockedNow, bundle.getBasisFingerprint())) {
            log.info("변경 묶음 적용 거절: 범위 일정이 검토 뒤 바뀜. bundleId={}", bundleId);
            throw new ConflictException(ErrorCode.CHANGE_BUNDLE_STALE, Map.of("reason", "SCHEDULE_CHANGED",
                    "rangeStart", bundle.getRangeStart(), "rangeEnd", bundle.getRangeEnd()));
        }
        lockAll(userId, ops);
        if (bundle.getRangeStart() != null) {
            String now = lockedNow;
            if (!Objects.equals(now, bundle.getBasisFingerprint())) {
                log.info("변경 묶음 적용 거절: 범위 일정이 검토 뒤 바뀜. bundleId={}", bundleId);
                throw new ConflictException(ErrorCode.CHANGE_BUNDLE_STALE,
                        Map.of("reason", "SCHEDULE_CHANGED", "rangeStart", bundle.getRangeStart(),
                                "rangeEnd", bundle.getRangeEnd()));
            }
        }
        for (int i = 0; i < ops.size(); i++) {
            ChangeCheck check = registry.moduleOf(ops.get(i)).recheck(userId, ops.get(i));
            if (!check.isOk()) {
                problems.add(problem(toApply.get(i), check.code(), check.message()));
            }
        }
        if (!problems.isEmpty()) {
            throw new ConflictException(ErrorCode.CHANGE_BUNDLE_STALE, problems);
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) {
            Map<String, Object> result = registry.moduleOf(ops.get(i)).apply(userId, ops.get(i));
            mapper.resolveItem(toApply.get(i).getItemId(), bundleId, ChangeBundleRows.APPLIED, toJson(result));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("itemId", toApply.get(i).getItemId());
            row.put("result", result);
            results.add(row);
        }
        for (ChangeBundleRows.Item item : items) {
            if (!selected.contains(item.getItemId())) {
                mapper.resolveItem(item.getItemId(), bundleId, ChangeBundleRows.DISMISSED, null);
            }
        }
        try {
            int resolved = mapper.resolveBundle(bundleId, userId, ChangeBundleRows.APPLIED, command.idempotencyKey(),
                    requestHash, toJson(Map.of("items", results)));
            if (resolved != 1) {
                throw new ConflictException(ErrorCode.CHANGE_BUNDLE_ALREADY_RESOLVED);
            }
        } catch (DuplicateKeyException e) {
            // 같은 키를 다른 묶음 적용에 쓰는 요청이 먼저 끝났다
            throw new ConflictException(ErrorCode.CHANGE_BUNDLE_IDEMPOTENCY_REUSED);
        }
        log.info("변경 묶음 적용: userId={}, bundleId={}, 적용={}개, 닫음={}개", userId, bundleId, ops.size(),
                items.size() - ops.size());
        return view(userId, bundleId);
    }

    /**
     * 선택한 항목을 겹친 가정 상태. 원본은 바뀌지 않는다. 시간 효과가 없는 항목은 겹침에 쓰지 않는다.
     *
     * @param itemIds 반영할 항목. null이면 지금 선택된 항목
     */
    @Transactional(readOnly = true)
    public Scenario scenario(Long userId, Long bundleId, Collection<Long> itemIds) {
        ChangeBundleRows.Bundle bundle = mapper.findBundle(bundleId, userId);
        if (bundle == null) {
            throw new NotFoundException(ErrorCode.CHANGE_BUNDLE_NOT_FOUND);
        }
        List<ChangeBundleRows.Item> items = mapper.findItems(bundleId, userId);
        Set<Long> chosen = itemIds == null
                ? items.stream().filter(i -> Boolean.TRUE.equals(i.getSelected())).map(ChangeBundleRows.Item::getItemId)
                .collect(Collectors.toSet())
                : new HashSet<>(itemIds);
        List<ScenarioProjection> projections = new ArrayList<>();
        StringBuilder content = new StringBuilder();
        List<Long> used = new ArrayList<>();
        for (ChangeBundleRows.Item item : items) {
            if (!chosen.contains(item.getItemId())) {
                continue;
            }
            used.add(item.getItemId());
            content.append(item.getOpType()).append(':').append(item.getOpJson()).append('\n');
            ChangeOp op = registry.read(item.getOpType(), item.getOpJson());
            if (op instanceof ScenarioProjection p) {
                projections.add(p);
            }
        }
        return Scenario.of(projections, new Scenario.ScenarioRef(bundleId, bundle.getRevision(), used,
                hash(content.toString())));
    }

    /** 확인만(도구 단계). 모듈 조회가 실패하면 "없음"이 아니라 READ_FAILED다. */
    public ChangeCheck safeCheck(Long userId, ChangeOp op) {
        try {
            return registry.moduleOf(op).check(userId, op);
        } catch (RuntimeException e) {
            log.warn("변경 확인 중 조회 실패: type={}, {}", op.type(), e.getClass().getSimpleName());
            return ChangeCheck.fail(ChangeCheck.READ_FAILED, "확인 중 서버에서 읽지 못했어요", op);
        }
    }

    // ===== 내부 =====

    private void lockAll(Long userId, List<ChangeOp> ops) {
        for (ChangeModule module : registry.modules()) {
            List<ChangeOp> mine = ops.stream().filter(op -> registry.moduleOf(op) == module).toList();
            if (!mine.isEmpty()) {
                module.lock(userId, mine);
            }
        }
    }

    private ChangeBundleRows.Bundle lockOpen(Long userId, Long bundleId, int expectedRevision) {
        ChangeBundleRows.Bundle bundle = mapper.findBundleForUpdate(bundleId, userId);
        if (bundle == null) {
            throw new NotFoundException(ErrorCode.CHANGE_BUNDLE_NOT_FOUND);
        }
        if (!ChangeBundleRows.PROPOSED.equals(bundle.getStatus())) {
            throw new ConflictException(ErrorCode.CHANGE_BUNDLE_ALREADY_RESOLVED);
        }
        if (bundle.getRevision() != expectedRevision) {
            throw new ConflictException(ErrorCode.CHANGE_BUNDLE_REVISION_CHANGED, Map.of("currentRevision", bundle.getRevision()));
        }
        return bundle;
    }

    /** 리비전을 올린다. 지문을 새로 잡으면 그 범위도 같은 갱신에서 함께 저장한다(범위와 지문이 어긋나지 않게). */
    private void bump(Long userId, ChangeBundleRows.Bundle bundle, String newFingerprint, LocalDate[] range) {
        int updated = mapper.bumpRevision(bundle.getBundleId(), userId, bundle.getRevision(), newFingerprint,
                newFingerprint == null ? null : range[0], newFingerprint == null ? null : range[1]);
        if (updated != 1) {
            throw new ConflictException(ErrorCode.CHANGE_BUNDLE_REVISION_CHANGED);
        }
    }

    private ChangeBundleView toView(ChangeBundleRows.Bundle bundle, List<ChangeBundleRows.Item> items) {
        Map<Integer, ChangeBundleRows.Item> bySeq = new LinkedHashMap<>();
        items.forEach(i -> bySeq.put(i.getSeq(), i));
        List<ChangeBundleView.Item> out = new ArrayList<>();
        for (ChangeBundleRows.Item item : items) {
            boolean blocked = depends(item).stream().anyMatch(d -> bySeq.get(d) == null
                    || !Boolean.TRUE.equals(bySeq.get(d).getSelected()));
            out.add(new ChangeBundleView.Item(item.getItemId(), item.getSeq(), item.getOpType(), item.getTargetKey(),
                    item.getSummary(), depends(item), item.getOrigin(), Boolean.TRUE.equals(item.getSelected()),
                    item.getStatus(), item.getCheckCode(), item.getCheckMessage(), blocked, readMap(item.getResultJson())));
        }
        return new ChangeBundleView(bundle.getBundleId(), bundle.getConversationId(), bundle.getSourceMessageId(),
                bundle.getStatus(), bundle.getRevision(), out, readMap(bundle.getApplyResultJson()));
    }

    private static List<Integer> depends(ChangeBundleRows.Item item) {
        if (item.getDependsOn() == null || item.getDependsOn().isBlank()) {
            return List.of();
        }
        List<Integer> out = new ArrayList<>();
        for (String s : item.getDependsOn().split(",")) {
            out.add(Integer.parseInt(s.trim()));
        }
        return out;
    }

    private static Map<String, Object> problem(ChangeBundleRows.Item item, String code, String message) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("itemId", item.getItemId());
        p.put("summary", item.getSummary());
        p.put("code", code);
        p.put("message", message);
        return p;
    }

    static LocalDate[] range(List<ChangeOp> ops) {
        LocalDate from = null;
        LocalDate to = null;
        for (ChangeOp op : ops) {
            for (LocalDate d : op.affectedDates()) {
                from = from == null || d.isBefore(from) ? d : from;
                to = to == null || d.isAfter(to) ? d : to;
            }
        }
        return new LocalDate[]{from, to == null ? null : to.plusDays(1)}; // 자정 넘김 회차까지 보도록 하루 뒤까지
    }

    private Map<String, Object> readMap(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return json.readValue(raw, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return null;
        }
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }
}
