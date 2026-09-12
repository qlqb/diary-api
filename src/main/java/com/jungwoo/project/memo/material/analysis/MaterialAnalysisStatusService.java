package com.jungwoo.project.memo.material.analysis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.ai.AiConsultationClient;
import com.jungwoo.project.memo.assignment.CourseAssignmentMapper;
import com.jungwoo.project.memo.assignment.domain.AssignmentConfirmStatus;
import com.jungwoo.project.memo.assignment.domain.CourseAssignment;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.material.CourseMaterialMapper;
import com.jungwoo.project.memo.material.MaterialSectionMapper;
import com.jungwoo.project.memo.material.MaterialService;
import com.jungwoo.project.memo.material.domain.AnalysisJobKind;
import com.jungwoo.project.memo.material.domain.AnalysisJobStatus;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.ExtractionStatus;
import com.jungwoo.project.memo.material.domain.MaterialAnalysisJob;
import com.jungwoo.project.memo.material.domain.MaterialSection;
import com.jungwoo.project.memo.material.domain.SectionRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 화면용 상태 조회. 작업 표를 "자료당 한 줄"로 접는다.
 *
 * <p>같은 자료에 작업이 여럿이면(옛 해시·옛 판) 현재 해시의 최신 CONTENT 작업이 그 자료의 상태다.
 * 추출 실패(NO_TEXT)는 작업이 아니라 자료의 상태라 여기서 따로 말한다.
 */
@Service
@RequiredArgsConstructor
public class MaterialAnalysisStatusService {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };
    private static final TypeReference<List<Map<String, Object>>> MAPS = new TypeReference<>() {
    };

    private final MaterialAnalysisJobService jobService;
    private final MaterialService materialService;
    private final CourseMaterialMapper courseMaterialMapper;
    private final MaterialSectionMapper sectionMapper;
    private final CourseAssignmentMapper assignmentMapper;
    private final AiConsultationClient aiConsultationClient;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public MaterialAnalysisOverviewResponse overview(Long userId) {
        List<CourseMaterial> materials = courseMaterialMapper.findAllByUserId(userId);
        List<MaterialAnalysisStatusResponse> rows = statuses(userId, materials);
        int queued = 0, running = 0, partial = 0, done = 0, failed = 0, unavailable = 0;
        for (MaterialAnalysisStatusResponse row : rows) {
            switch (row.getState()) {
                case "QUEUED", "PAUSED" -> queued++;
                case "RUNNING" -> running++;
                case "PARTIAL" -> partial++;
                case "DONE" -> done++;
                case "FAILED", "NO_TEXT" -> failed++;
                case "UNAVAILABLE" -> unavailable++;
                default -> {
                }
            }
        }
        return MaterialAnalysisOverviewResponse.builder()
                .paused(jobService.isPaused(userId))
                .serviceAvailable(aiConsultationClient.isConfigured())
                .queued(queued).running(running).partial(partial).done(done).failed(failed).unavailable(unavailable)
                .materials(rows)
                .build();
    }

    @Transactional(readOnly = true)
    public MaterialAnalysisStatusResponse forMaterial(Long userId, Long materialId) {
        CourseMaterial material = materialService.getActiveOwned(userId, materialId);
        return statuses(userId, List.of(material)).get(0);
    }

    @Transactional(readOnly = true)
    public List<MaterialAnalysisStatusResponse> statuses(Long userId, List<CourseMaterial> materials) {
        if (materials.isEmpty()) {
            return List.of();
        }
        List<Long> ids = materials.stream().map(CourseMaterial::getMaterialId).toList();
        Map<Long, List<MaterialAnalysisJob>> jobsByMaterial = new HashMap<>();
        for (MaterialAnalysisJob job : jobService.findByMaterials(userId, ids)) {
            jobsByMaterial.computeIfAbsent(job.getMaterialId(), k -> new ArrayList<>()).add(job);
        }
        Map<Long, Integer> sectionCounts = new HashMap<>();
        for (MaterialSection section : sectionMapper.findActiveByMaterialIds(ids, userId)) {
            sectionCounts.merge(section.getMaterialId(), 1, Integer::sum);
        }
        List<MaterialAnalysisStatusResponse> out = new ArrayList<>();
        for (CourseMaterial material : materials) {
            out.add(statusOf(userId, material, jobsByMaterial.getOrDefault(material.getMaterialId(), List.of()),
                    sectionCounts.getOrDefault(material.getMaterialId(), 0)));
        }
        return out;
    }

    private MaterialAnalysisStatusResponse statusOf(Long userId, CourseMaterial material, List<MaterialAnalysisJob> jobs,
                                                    int sectionCount) {
        String hash = material.getFileHash();
        MaterialAnalysisJob content = jobs.stream()
                .filter(j -> j.getJobKind() == AnalysisJobKind.CONTENT)
                .filter(j -> hash == null || hash.equals(j.getFileHash()))
                .max(Comparator.comparing(MaterialAnalysisJob::getAnalysisVersion).thenComparing(MaterialAnalysisJob::getJobId))
                .orElse(null);
        String state;
        if (material.getExtractionStatus() != ExtractionStatus.SUCCESS) {
            state = "NO_TEXT";
        } else if (content == null) {
            state = "NONE";
        } else {
            state = content.getStatus().name();
        }
        List<MaterialAnalysisStatusResponse.LinkState> links = new ArrayList<>();
        for (MaterialAnalysisJob link : jobs.stream().filter(j -> j.getJobKind() == AnalysisJobKind.LINK)
                .filter(j -> hash == null || hash.equals(j.getFileHash()))
                .sorted(Comparator.comparing(MaterialAnalysisJob::getJobId).reversed()).toList()) {
            if (links.stream().anyMatch(l -> Objects.equals(l.getCourseId(), link.getCourseId()))) {
                continue; // 프로젝트당 최신 하나만
            }
            links.add(MaterialAnalysisStatusResponse.LinkState.builder()
                    .courseId(link.getCourseId()).state(link.getStatus().name())
                    .proposalId(link.getResultRefId()).message(link.getErrorMessage()).build());
        }
        int candidates = 0;
        for (CourseAssignment a : assignmentMapper.findByMaterialId(material.getMaterialId(), userId)) {
            if (a.getConfirmStatus() == AssignmentConfirmStatus.CANDIDATE) {
                candidates++;
            }
        }
        return MaterialAnalysisStatusResponse.builder()
                .materialId(material.getMaterialId())
                .state(state)
                .totalChunks(content == null ? null : content.getTotalChunks())
                .completedChunks(content == null ? null : content.getCompletedChunks())
                .errorCode(content == null ? null : content.getErrorCode())
                .message(content == null ? material.getExtractionError() : content.getErrorMessage())
                .sectionCount(sectionCount)
                .assignmentCandidateCount(candidates)
                .pageCount(material.getPageCount())
                .updatedAt(content == null ? null : content.getUpdatedAt())
                .finishedAt(content == null ? null : content.getFinishedAt())
                .linkStates(links)
                .build();
    }

    @Transactional(readOnly = true)
    public List<MaterialSectionResponse> sections(Long userId, Long materialId) {
        CourseMaterial material = materialService.getActiveOwned(userId, materialId);
        if (material.getFileHash() == null) {
            return List.of();
        }
        List<MaterialSectionResponse> out = new ArrayList<>();
        for (MaterialSection section : sectionMapper.findActiveByMaterialIdAndHash(materialId, material.getFileHash())) {
            out.add(toResponse(section));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public MaterialSectionResponse section(Long userId, Long sectionId) {
        MaterialSection section = sectionMapper.findByIdAndUserId(sectionId, userId);
        if (section == null) {
            throw new NotFoundException(ErrorCode.MATERIAL_SECTION_NOT_FOUND);
        }
        return toResponse(section);
    }

    public MaterialSectionResponse toResponse(MaterialSection section) {
        List<String> roles = read(section.getRolesJson(), STRINGS);
        List<String> labels = roles.stream().map(SectionRole::parseOne).filter(Objects::nonNull)
                .map(SectionRole::label).distinct().toList();
        return MaterialSectionResponse.of(section, roles, labels, read(section.getDateCandidatesJson(), MAPS));
    }

    private <T> List<T> read(String json, TypeReference<List<T>> type) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 사용자 재시도. 자료의 CONTENT 작업을 앞으로 당긴다. */
    @Transactional
    public MaterialAnalysisStatusResponse retry(Long userId, Long materialId) {
        CourseMaterial material = materialService.getActiveOwned(userId, materialId);
        jobService.retryContent(material);
        return statuses(userId, List.of(courseMaterialMapper.findByIdAndUserId(materialId, userId))).get(0);
    }
}
