package com.jungwoo.project.memo.plan.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.common.BuildVersion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 계획 생성의 실제 전달 기록. "목록에 존재함 → 조회 성공 → 모델 입력에 전달됨" 중 세 번째를 검증할 수 있게 한다.
 *
 * <p>기록은 생성의 부속물이다 — 쓰기가 실패해도 생성은 실패하지 않는다. 인증 정보는 프롬프트에 없고 여기에도 없다.
 * 모델의 비공개 사고과정은 저장하지 않는다(입력과, 사용자에게 설명할 결정 근거만).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanGenerationTraceService {

    private final PlanGenerationTraceMapper mapper;
    private final BuildVersion buildVersion;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${plan.trace.enabled:true}")
    private boolean enabled = true;

    @Value("${plan.trace.retention-days:30}")
    private int retentionDays = 30;

    public String commit() {
        return buildVersion.commit();
    }

    /** 프로젝트 하나의 집계. */
    public record CourseCount(Long courseId, String courseTitle, int candidates, Integer shown, int selected, int delivered) {
    }

    /**
     * 호출 하나.
     *
     * @param sectionIds          입력에 줄(목록)로 실린 구간 id
     * @param deliveredSectionIds 입력에 원문으로 실린 구간 id(계획 호출만)
     */
    public record Entry(String callKind, String modelName, Integer estimatedTokens, String systemPrompt,
                        String userPrompt, Collection<Long> sectionIds, Collection<Long> topicIds,
                        Collection<Long> deliveredSectionIds, Collection<Long> materialIds, List<CourseCount> perCourse) {
    }

    /** 한 회차의 호출들을 순서대로 남긴다. */
    public void save(Long userId, String generationId, List<Entry> entries) {
        if (!enabled || entries == null || entries.isEmpty()) {
            return;
        }
        try {
            int order = 1;
            for (Entry e : entries) {
                PlanGenerationTrace row = new PlanGenerationTrace();
                row.setUserId(userId);
                row.setGenerationId(generationId);
                row.setCallKind(e.callKind());
                row.setCallOrder(order++);
                row.setApiCommit(buildVersion.commit());
                row.setModelName(e.modelName());
                row.setEstimatedTokens(e.estimatedTokens());
                row.setSystemPrompt(e.systemPrompt());
                row.setUserPrompt(e.userPrompt());
                row.setPromptSha256(sha256((e.systemPrompt() == null ? "" : e.systemPrompt()) + "\n"
                        + (e.userPrompt() == null ? "" : e.userPrompt())));
                Map<String, Object> shown = new LinkedHashMap<>();
                shown.put("sectionIds", sorted(e.sectionIds()));
                shown.put("topicIds", sorted(e.topicIds()));
                shown.put("deliveredSectionIds", sorted(e.deliveredSectionIds()));
                shown.put("perCourse", e.perCourse() == null ? List.of() : e.perCourse());
                row.setShownJson(objectMapper.writeValueAsString(shown));
                row.setMaterialIds(token(e.materialIds()));
                mapper.insert(row);
            }
            if (retentionDays > 0) {
                mapper.deleteOlderThan(LocalDateTime.now().minusDays(retentionDays));
            }
        } catch (Exception ex) {
            log.warn("계획 생성 전달 기록을 남기지 못했다(생성은 계속한다): generationId={}, {}", generationId,
                    ex.getClass().getSimpleName());
        }
    }

    public List<PlanGenerationTrace> find(Long userId, String generationId) {
        if (generationId == null) {
            return List.of();
        }
        List<PlanGenerationTrace> rows = mapper.findByGenerationIdAndUserId(generationId, userId);
        return rows == null ? List.of() : rows;
    }

    /** 자료 삭제 시 그 자료가 실린 입력의 전문을 지운다. */
    public void purgeForMaterial(Long userId, Long materialId) {
        try {
            int purged = mapper.purgeTextByMaterial(userId, "," + materialId + ",");
            if (purged > 0) {
                log.info("자료 삭제에 따라 계획 생성 전달 기록의 전문 {}건을 지웠다. materialId={}", purged, materialId);
            }
        } catch (Exception ex) {
            log.warn("계획 생성 전달 기록의 전문을 지우지 못했다: materialId={}, {}", materialId, ex.getClass().getSimpleName());
        }
    }

    private static List<Long> sorted(Collection<Long> ids) {
        return ids == null ? List.of() : new ArrayList<>(new TreeSet<>(ids));
    }

    private static String token(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(",");
        new TreeSet<>(ids).forEach(id -> sb.append(id).append(','));
        return sb.toString();
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return null;
        }
    }
}
