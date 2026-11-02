package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.ai.dto.AiProposalApplyRequest.EditedProposalItem;
import com.jungwoo.project.memo.ai.dto.AiProposalItemResponse;
import com.jungwoo.project.memo.plan.dto.PlanReviewState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 초안을 다시 만들 때 사용자의 검토 상태(직접 고친 값·뺀 항목·제목·답)를 새 초안으로 옮긴다.
 *
 * <p>왜: 다시 만들기는 새 제안(새 항목 id)을 만든다. 예전에는 검토 상태가 옛 제안에만 남아, 사용자가 "20분으로 줄임"이라고
 * 고친 것이 재생성 한 번에 사라졌다. 전체 재생성이 수동 편집을 날리면 안 된다.
 *
 * <p>규칙:
 * <ul>
 *   <li>항목은 제목(공백·기호 무시)과 프로젝트로 짝짓는다. 의미가 비슷하다는 추측으로 짝짓지 않는다.</li>
 *   <li>사용자가 고친 필드는 새 항목에도 사용자 값을 둔다(기본은 사용자 값 유지). 새 초안의 AI 값이 옛 AI 값과 달라졌으면
 *       충돌로 알려 사용자가 비교해 고를 수 있게 한다 — 조용히 어느 한쪽으로 덮지 않는다.</li>
 *   <li>사용자가 뺀 항목과 같은 항목이 새 초안에 또 나오면 뺀 상태를 유지한다.</li>
 *   <li>사용자가 고친 항목이 새 초안에 없으면 그 사실을 알린다(만들어 넣지 않는다 — 새 판단이 그 항목을 뺀 것이다).</li>
 * </ul>
 */
public final class ReviewStateCarryOver {

    private ReviewStateCarryOver() {
    }

    /** @param fields 옮긴 필드 이름(expectedMinutes 등) */
    public record CarriedEdit(String title, List<String> fields) {
    }

    /**
     * @param field     충돌한 필드. 항목 자체가 새 초안에 없으면 "item"
     * @param yours     사용자가 고친 값(유지된다)
     * @param suggested 새 초안이 제안한 값. 항목이 없으면 null
     */
    public record EditConflict(String title, String field, String yours, String suggested) {
    }

    /** @param state 새 제안에 저장할 검토 상태. 옮길 것이 없으면 null */
    public record Result(PlanReviewState state, List<CarriedEdit> carried, List<EditConflict> conflicts) {
        public static Result none() {
            return new Result(null, List.of(), List.of());
        }
    }

    public static Result carry(PlanReviewState old, List<AiProposalItemResponse> oldItems,
                               List<AiProposalItemResponse> newItems) {
        if (old == null) {
            return Result.none();
        }
        Map<Long, AiProposalItemResponse> oldById = new HashMap<>();
        for (AiProposalItemResponse item : oldItems == null ? List.<AiProposalItemResponse>of() : oldItems) {
            oldById.put(item.getProposalItemId(), item);
        }
        Map<String, AiProposalItemResponse> newByKey = new HashMap<>();
        for (AiProposalItemResponse item : newItems == null ? List.<AiProposalItemResponse>of() : newItems) {
            newByKey.putIfAbsent(key(item), item);
        }

        List<EditedProposalItem> editedNext = new ArrayList<>();
        List<CarriedEdit> carried = new ArrayList<>();
        List<EditConflict> conflicts = new ArrayList<>();
        for (EditedProposalItem edit : old.editedItems() == null ? List.<EditedProposalItem>of() : old.editedItems()) {
            AiProposalItemResponse before = oldById.get(edit.getProposalItemId());
            if (before == null) {
                continue;
            }
            AiProposalItemResponse after = newByKey.get(key(before));
            if (after == null) {
                conflicts.add(new EditConflict(before.getTitle(), "item", "직접 고친 항목", null));
                continue;
            }
            EditedProposalItem next = new EditedProposalItem();
            next.setProposalItemId(after.getProposalItemId());
            List<String> fields = new ArrayList<>();
            if (edit.getExpectedMinutes() != null && !Objects.equals(edit.getExpectedMinutes(), before.getExpectedMinutes())) {
                next.setExpectedMinutes(edit.getExpectedMinutes());
                fields.add("expectedMinutes");
                if (!Objects.equals(after.getExpectedMinutes(), before.getExpectedMinutes())
                        && !Objects.equals(after.getExpectedMinutes(), edit.getExpectedMinutes())) {
                    conflicts.add(new EditConflict(after.getTitle(), "expectedMinutes", edit.getExpectedMinutes() + "분",
                            after.getExpectedMinutes() + "분"));
                }
            }
            if (edit.getPriority() != null && !Objects.equals(edit.getPriority(), before.getPriority())) {
                next.setPriority(edit.getPriority());
                fields.add("priority");
                if (!Objects.equals(after.getPriority(), before.getPriority())
                        && !Objects.equals(after.getPriority(), edit.getPriority())) {
                    conflicts.add(new EditConflict(after.getTitle(), "priority", edit.getPriority(), after.getPriority()));
                }
            }
            if (edit.getTitle() != null && !Objects.equals(edit.getTitle(), before.getTitle())) {
                next.setTitle(edit.getTitle());
                fields.add("title");
            }
            if (edit.getDescription() != null && !Objects.equals(edit.getDescription(), before.getDescription())) {
                next.setDescription(edit.getDescription());
                fields.add("description");
            }
            if (edit.getScheduledDate() != null || edit.getScheduledStartAt() != null) {
                next.setPlacementType(edit.getPlacementType());
                next.setScheduledDate(edit.getScheduledDate());
                next.setScheduledStartAt(edit.getScheduledStartAt());
                next.setScheduledEndAt(edit.getScheduledEndAt());
                fields.add("schedule");
            }
            if (!fields.isEmpty()) {
                editedNext.add(next);
                carried.add(new CarriedEdit(after.getTitle(), fields));
            }
        }

        List<Long> excludedNext = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Long excludedId : old.excludedProposalItemIds() == null ? List.<Long>of() : old.excludedProposalItemIds()) {
            AiProposalItemResponse before = oldById.get(excludedId);
            AiProposalItemResponse after = before == null ? null : newByKey.get(key(before));
            if (after != null && seen.add(after.getProposalItemId())) {
                excludedNext.add(after.getProposalItemId());
                carried.add(new CarriedEdit(after.getTitle(), List.of("excluded")));
            }
        }

        boolean hasAnswers = old.answers() != null && !old.answers().isEmpty();
        if (editedNext.isEmpty() && excludedNext.isEmpty() && old.title() == null && !hasAnswers) {
            return new Result(null, carried, conflicts);
        }
        PlanReviewState state = new PlanReviewState(1, old.title(), excludedNext, editedNext,
                hasAnswers ? old.answers() : Map.of(), java.time.LocalDateTime.now());
        return new Result(state, carried, conflicts);
    }

    private static String key(AiProposalItemResponse item) {
        String title = item.getTitle() == null ? "" : item.getTitle().toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}·…]+", "");
        // 일일 반복처럼 제목이 같고 날짜가 다른 항목은 서로 다른 실행이다 — 날짜가 의미인 항목은 날짜까지 본다.
        String date = item.getPlacementType() == com.jungwoo.project.memo.execution.domain.PlacementType.DATE_ONLY
                && item.getTargetDate() != null ? "|" + item.getTargetDate() : "";
        return item.getCourseId() + "|" + title + date;
    }
}
