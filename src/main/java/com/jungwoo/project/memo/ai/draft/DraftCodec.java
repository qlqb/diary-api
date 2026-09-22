package com.jungwoo.project.memo.ai.draft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * {@link DraftState} ↔ {@link AiConversationDraft}(JSON 문자열 컬럼) 변환. 순수 자바.
 *
 * <p>fields JSON 모양:
 * <pre>{"durationMinutes": {"value": 60, "source": "USER", "reason": null, "confirmationRequired": false}, ...}</pre>
 */
public final class DraftCodec {

    private final ObjectMapper objectMapper;

    public DraftCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public DraftState fromEntity(AiConversationDraft entity) {
        DraftState state = new DraftState();
        state.setDraftId(entity.getDraftId());
        state.setUserId(entity.getUserId());
        state.setConversationId(entity.getConversationId());
        state.setDraftGroupId(entity.getDraftGroupId());
        state.setLabel(entity.getLabel());
        state.setStatus(entity.getStatus() != null ? entity.getStatus() : DraftStatus.OPEN);
        state.setAskCount(entity.getAskCount());
        state.setCreatedAt(entity.getCreatedAt());
        state.setUpdatedAt(entity.getUpdatedAt());
        state.retype(entity.getDraftType());
        state.getFields().putAll(readFields(entity.getFields()));
        state.setMissingRequired(readStrings(entity.getMissingRequired()));
        state.setPromotedSuggestionIds(readLongs(entity.getPromotedSuggestionIds()));
        return state;
    }

    public AiConversationDraft toEntity(DraftState state) {
        return AiConversationDraft.builder()
                .draftId(state.getDraftId())
                .userId(state.getUserId())
                .conversationId(state.getConversationId())
                .draftGroupId(state.getDraftGroupId())
                .draftType(state.getType())
                .label(state.getLabel())
                .status(state.getStatus())
                .fields(writeFields(state.getFields()))
                .missingRequired(writeJson(state.getMissingRequired()))
                .askCount(state.getAskCount())
                .promotedSuggestionIds(state.getPromotedSuggestionIds().isEmpty()
                        ? null : writeJson(state.getPromotedSuggestionIds()))
                .createdAt(state.getCreatedAt())
                .updatedAt(state.getUpdatedAt())
                .build();
    }

    public Map<String, DraftField> readFields(String json) {
        Map<String, DraftField> result = new java.util.LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return result;
        }
        JsonNode root = readTree(json);
        if (!root.isObject()) {
            return result;
        }
        Iterator<Map.Entry<String, JsonNode>> it = root.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            JsonNode node = entry.getValue();
            FieldSource source;
            try {
                source = FieldSource.valueOf(node.path("source").asText("SYSTEM"));
            } catch (IllegalArgumentException e) {
                source = FieldSource.SYSTEM;
            }
            String reason = node.hasNonNull("reason") ? node.get("reason").asText() : null;
            result.put(entry.getKey(), new DraftField(node.get("value"), source, reason,
                    node.path("confirmationRequired").asBoolean(false)));
        }
        return result;
    }

    public String writeFields(Map<String, DraftField> fields) {
        ObjectNode root = objectMapper.createObjectNode();
        for (Map.Entry<String, DraftField> entry : fields.entrySet()) {
            DraftField field = entry.getValue();
            ObjectNode node = root.putObject(entry.getKey());
            node.set("value", field.value() != null ? field.value() : objectMapper.nullNode());
            node.put("source", field.source().name());
            if (field.reason() != null) {
                node.put("reason", field.reason());
            } else {
                node.putNull("reason");
            }
            node.put("confirmationRequired", field.confirmationRequired());
        }
        return root.toString();
    }

    private List<String> readStrings(String json) {
        List<String> result = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return result;
        }
        JsonNode root = readTree(json);
        if (root.isArray()) {
            for (JsonNode node : (ArrayNode) root) {
                result.add(node.asText());
            }
        }
        return result;
    }

    private List<Long> readLongs(String json) {
        List<Long> result = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return result;
        }
        JsonNode root = readTree(json);
        if (root.isArray()) {
            for (JsonNode node : (ArrayNode) root) {
                if (node.canConvertToLong()) {
                    result.add(node.asLong());
                }
            }
        }
        return result;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("draft JSON 직렬화 실패", e);
        }
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("draft JSON을 읽지 못했다", e);
        }
    }
}
