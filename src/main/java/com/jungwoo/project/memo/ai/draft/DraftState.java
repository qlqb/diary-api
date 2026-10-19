package com.jungwoo.project.memo.ai.draft;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 한 턴 안에서 서버가 다루는 draft의 작업용 상태. 저장 형태는 {@link AiConversationDraft}이고
 * 변환은 {@link DraftCodec}이 한다.
 *
 * <p>모델이 이번 턴에 새로 만든 draft는 draftId가 아직 없고 tempId("new-0")로만 참조된다.
 * 실제 id는 턴 마무리 트랜잭션에서 INSERT한 뒤에 생긴다.
 */
public class DraftState {

    private Long draftId;
    private String tempId;
    private Long userId;
    private Long conversationId;
    private Long draftGroupId;
    /** 새 draft가 기존 draft의 그룹에 붙을 때 그 draft의 id(또는 tempId). 저장 시 그룹 id로 풀린다. */
    private String sameGroupAs;
    private DraftType type;
    private String label;
    private DraftStatus status = DraftStatus.OPEN;
    private final LinkedHashMap<String, DraftField> fields = new LinkedHashMap<>();
    private List<String> missingRequired = new ArrayList<>();
    private int askCount;
    private List<Long> promotedSuggestionIds = new ArrayList<>();
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static DraftState newDraft(String tempId, Long userId, Long conversationId, DraftType type, String label) {
        DraftState state = new DraftState();
        state.tempId = tempId;
        state.userId = userId;
        state.conversationId = conversationId;
        state.type = type;
        state.label = label;
        return state;
    }

    public boolean isNew() {
        return draftId == null;
    }

    /** 프롬프트·op 참조에 쓰는 표시 id. 새 draft는 tempId. */
    public String refId() {
        return draftId != null ? String.valueOf(draftId) : tempId;
    }

    public boolean matches(String ref) {
        if (ref == null) {
            return false;
        }
        String trimmed = ref.trim();
        if (draftId != null && trimmed.equals(String.valueOf(draftId))) {
            return true;
        }
        return tempId != null && trimmed.equals(tempId);
    }

    public DraftReadiness readiness() {
        return DraftSlotRegistry.readiness(missingRequired, fields);
    }

    public boolean isOpen() {
        return status == DraftStatus.OPEN;
    }

    /** RETYPE 뒤 새 타입에 없는 필드를 전부 지운다 — 모델이 CLEAR를 빠뜨려도 잔재가 남지 않게. */
    public void retype(DraftType newType) {
        this.type = newType;
        fields.keySet().retainAll(DraftSlotRegistry.allowedFields(newType));
    }

    public List<String> unconfirmedFields() {
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, DraftField> entry : fields.entrySet()) {
            if (entry.getValue().confirmationRequired()) {
                names.add(entry.getKey());
            }
        }
        return names;
    }

    // ---- getters/setters ----

    public Long getDraftId() {
        return draftId;
    }

    public void setDraftId(Long draftId) {
        this.draftId = draftId;
    }

    public String getTempId() {
        return tempId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public void setConversationId(Long conversationId) {
        this.conversationId = conversationId;
    }

    public Long getDraftGroupId() {
        return draftGroupId;
    }

    public void setDraftGroupId(Long draftGroupId) {
        this.draftGroupId = draftGroupId;
    }

    public String getSameGroupAs() {
        return sameGroupAs;
    }

    public void setSameGroupAs(String sameGroupAs) {
        this.sameGroupAs = sameGroupAs;
    }

    public DraftType getType() {
        return type;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public DraftStatus getStatus() {
        return status;
    }

    public void setStatus(DraftStatus status) {
        this.status = status;
    }

    public LinkedHashMap<String, DraftField> getFields() {
        return fields;
    }

    public List<String> getMissingRequired() {
        return missingRequired;
    }

    public void setMissingRequired(List<String> missingRequired) {
        this.missingRequired = missingRequired != null ? new ArrayList<>(missingRequired) : new ArrayList<>();
    }

    public int getAskCount() {
        return askCount;
    }

    public void setAskCount(int askCount) {
        this.askCount = askCount;
    }

    public List<Long> getPromotedSuggestionIds() {
        return promotedSuggestionIds;
    }

    public void setPromotedSuggestionIds(List<Long> promotedSuggestionIds) {
        this.promotedSuggestionIds = promotedSuggestionIds != null ? new ArrayList<>(promotedSuggestionIds) : new ArrayList<>();
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
