package com.jungwoo.project.memo.assignment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.assignment.domain.AssignmentConfirmStatus;
import com.jungwoo.project.memo.assignment.domain.CourseAssignment;
import com.jungwoo.project.memo.assignment.domain.DueKind;
import com.jungwoo.project.memo.assignment.domain.DueSource;
import com.jungwoo.project.memo.assignment.dto.AssignmentRequests;
import com.jungwoo.project.memo.assignment.dto.AssignmentResponse;
import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.course.CourseService;
import com.jungwoo.project.memo.material.CourseMaterialMapper;
import com.jungwoo.project.memo.material.MaterialSectionMapper;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.MaterialSection;
import com.jungwoo.project.memo.material.domain.MaterialStatus;
import com.jungwoo.project.memo.routine.RoutineOccurrenceService;
import com.jungwoo.project.memo.routine.domain.RoutineOccurrence;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 과제: 확인 질문의 답, 마감, 완료 체크. 원본은 course_assignments 하나다 — 오늘·프로젝트·계획이
 * 전부 이 표를 읽고, 화면별 복제 Todo를 만들지 않는다.
 *
 * <p>AI가 하는 일은 후보를 만드는 것까지다({@link #upsertCandidateFromSection}). 과제 여부·마감·완료는
 * 전부 사용자 동작이고, 재분석은 사용자가 손댄 값을 덮지 않는다(title_edited/due_edited, CANDIDATE만 갱신).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CourseAssignmentService {

    private static final TypeReference<List<Map<String, Object>>> ESTIMATE_LIST = new TypeReference<>() {
    };

    private final CourseAssignmentMapper assignmentMapper;
    private final CourseService courseService;
    private final CourseMaterialMapper courseMaterialMapper;
    private final MaterialSectionMapper sectionMapper;
    private final RoutineOccurrenceService routineOccurrenceService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Value("${ai.context.default-time-zone:Asia/Seoul}")
    private String defaultTimeZoneId = "Asia/Seoul";

    // ===== 분석기 진입점 =====

    /**
     * 제출 단서가 있는 구간에서 과제 후보를 만든다. 같은 구간(dedupe_key)이 다시 오면 CANDIDATE인
     * 경우에만 원문 인용·추정을 갱신하고, 사용자가 답했거나 고친 값은 건드리지 않는다.
     *
     * <p>마감은 원문에 연도까지 명시된 제출 날짜가 있을 때만 미리 채운다(SOURCE). 그 외의 날짜 단서는
     * 전부 추정 후보(due_estimate_json)로만 남는다 — 사용자가 확정하기 전에는 마감이 아니다.
     *
     * @return 새로 만들었으면 true
     */
    @Transactional
    public boolean upsertCandidateFromSection(MaterialSection section, Long courseId, LocalDate documentDate) {
        String dedupeKey = "m" + section.getMaterialId() + ":" + section.getDedupeKey();
        List<Map<String, Object>> dates = readEstimates(section.getDateCandidatesJson());
        Map<String, Object> explicit = null;
        List<Map<String, Object>> estimates = new ArrayList<>();
        for (Map<String, Object> date : dates) {
            if (!"DUE".equals(date.get("kind"))) {
                continue;
            }
            boolean relative = Boolean.TRUE.equals(date.get("relative"));
            LocalDate iso = parseDate(date.get("isoDate"));
            if (explicit == null && iso != null && !relative) {
                explicit = date;
            } else {
                Map<String, Object> estimate = new HashMap<>(date);
                estimate.put("source", "MATERIAL_TEXT");
                if (documentDate != null) {
                    estimate.put("documentDate", documentDate.toString());
                }
                estimates.add(estimate);
            }
        }
        String title = section.getSectionLabel() != null && !section.getDisplayTitle().contains(section.getSectionLabel())
                ? section.getSectionLabel() + " · " + section.getDisplayTitle()
                : section.getDisplayTitle();
        String quote = section.getAssignmentQuote() != null ? section.getAssignmentQuote() : section.getExcerpt();
        String dueQuote = explicit != null ? String.valueOf(explicit.get("text"))
                : estimates.isEmpty() ? null : String.valueOf(estimates.get(0).get("text"));
        String estimateJson = estimates.isEmpty() ? null : writeJson(estimates);

        CourseAssignment existing = assignmentMapper.findByDedupeKey(section.getUserId(), dedupeKey);
        if (existing != null) {
            assignmentMapper.refreshCandidate(existing.getAssignmentId(), cut(quote, 500), cut(dueQuote, 300),
                    estimateJson, null, courseId);
            return false;
        }
        CourseAssignment candidate = CourseAssignment.builder()
                .userId(section.getUserId()).courseId(courseId)
                .materialId(section.getMaterialId()).sectionId(section.getSectionId())
                .title(cut(title, 300)).sourceQuote(cut(quote, 500))
                .confirmStatus(AssignmentConfirmStatus.CANDIDATE)
                .dueKind(explicit != null ? DueKind.DATE : DueKind.UNKNOWN)
                .dueDate(explicit != null ? parseDate(explicit.get("isoDate")) : null)
                .dueSource(explicit != null ? DueSource.SOURCE : null)
                .dueQuote(cut(dueQuote, 300))
                .dueEstimateJson(estimateJson)
                .dedupeKey(dedupeKey)
                .build();
        int inserted = assignmentMapper.insertIgnore(candidate);
        if (inserted == 0) {
            // 두 작업이 같은 구간을 동시에 냈다. 먼저 들어간 행이 원본이다.
            return false;
        }
        log.info("과제 후보 등록: assignmentId={}, materialId={}, sectionId={}, dueKind={}",
                candidate.getAssignmentId(), section.getMaterialId(), section.getSectionId(), candidate.getDueKind());
        return true;
    }

    /**
     * LINK 분석이 프로젝트 맥락을 알게 됐을 때: 프로젝트·토픽을 채우고(비어 있을 때만), 상대 표현
     * ("다음 수업까지")을 확인된 수업 일정으로 해석할 수 있으면 추정 후보에 날짜를 붙인다.
     * 문서 날짜를 모르면 해석하지 않는다 — 업로드일을 문서 날짜로 가정하지 않는다.
     */
    @Transactional
    public void attachContext(Long assignmentId, Long userId, Long courseId, Long topicId, LocalDate documentDate,
                              Long duplicateHintId) {
        CourseAssignment assignment = assignmentMapper.findByIdAndUserId(assignmentId, userId);
        if (assignment == null) {
            return;
        }
        assignmentMapper.attachCourseIfMissing(assignmentId, courseId, topicId);
        if (assignment.getConfirmStatus() != AssignmentConfirmStatus.CANDIDATE || assignment.isDueEdited()) {
            return;
        }
        List<Map<String, Object>> estimates = readEstimates(assignment.getDueEstimateJson());
        boolean changed = false;
        if (documentDate != null && courseId != null) {
            for (Map<String, Object> estimate : estimates) {
                if (!Boolean.TRUE.equals(estimate.get("relative")) || estimate.get("isoDate") != null) {
                    continue;
                }
                String text = String.valueOf(estimate.get("text"));
                if (text.contains("다음 수업") || text.contains("다음 시간") || text.contains("다음 주 수업")) {
                    LocalDate next = nextClassAfter(userId, courseId, documentDate);
                    if (next != null) {
                        estimate.put("isoDate", next.toString());
                        estimate.put("basis", "문서 날짜 " + documentDate + " 기준으로 확인된 다음 수업일");
                        estimate.put("source", "CLASS_SCHEDULE");
                        changed = true;
                    }
                }
            }
        }
        if (duplicateHintId != null && assignment.getDuplicateOfAssignmentId() == null) {
            CourseAssignment other = assignmentMapper.findByIdAndUserId(duplicateHintId, userId);
            if (other != null && !other.getAssignmentId().equals(assignmentId)
                    && Objects.equals(other.getCourseId(), courseId == null ? assignment.getCourseId() : courseId)) {
                // 힌트만 남긴다. 상태는 CANDIDATE 그대로 — 병합은 사용자가 「같은 과제예요」로 정한다.
                assignmentMapper.updateConfirmStatus(assignmentId, userId, AssignmentConfirmStatus.CANDIDATE.name(),
                        duplicateHintId, assignment.getVersion());
                assignment = assignmentMapper.findByIdAndUserId(assignmentId, userId);
            }
        }
        if (changed) {
            assignmentMapper.refreshCandidate(assignmentId, null, null, writeJson(estimates), topicId, courseId);
        }
    }

    private LocalDate nextClassAfter(Long userId, Long courseId, LocalDate from) {
        try {
            for (RoutineOccurrence occurrence : routineOccurrenceService.expand(userId, from, from.plusDays(14))) {
                if (courseId.equals(occurrence.courseId()) && occurrence.startAt().toLocalDate().isAfter(from)) {
                    return occurrence.startAt().toLocalDate();
                }
            }
        } catch (Exception e) {
            log.debug("다음 수업 계산 실패: courseId={}", courseId);
        }
        return null;
    }

    // ===== 조회 =====

    @Transactional(readOnly = true)
    public List<AssignmentResponse> listByCourse(Long userId, Long courseId) {
        courseService.getOwned(userId, courseId);
        return toResponses(userId, assignmentMapper.findByCourseId(courseId, userId));
    }

    /** 오늘/한눈에: 확정·미완료 과제 전부(마감 순, 마감 없는 것은 뒤). */
    @Transactional(readOnly = true)
    public List<AssignmentResponse> listOpen(Long userId) {
        return toResponses(userId, assignmentMapper.findOpenConfirmed(userId));
    }

    /** 계획 생성 입력용 원본. 프로젝트 여러 개. */
    @Transactional(readOnly = true)
    public List<CourseAssignment> findByCourses(Long userId, List<Long> courseIds) {
        if (courseIds == null || courseIds.isEmpty()) {
            return List.of();
        }
        return assignmentMapper.findByCourseIds(courseIds, userId);
    }

    @Transactional(readOnly = true)
    public AssignmentResponse get(Long userId, Long assignmentId) {
        return toResponses(userId, List.of(getOwned(userId, assignmentId))).get(0);
    }

    // ===== 사용자 동작 =====

    @Transactional
    public AssignmentResponse answer(Long userId, Long assignmentId, AssignmentRequests.Answer request) {
        CourseAssignment assignment = getOwned(userId, assignmentId);
        Long duplicateOf = null;
        if (request.getAnswer() == AssignmentConfirmStatus.DUPLICATE) {
            if (request.getDuplicateOfAssignmentId() == null) {
                throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
            }
            CourseAssignment other = getOwned(userId, request.getDuplicateOfAssignmentId());
            if (other.getAssignmentId().equals(assignmentId)) {
                throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
            }
            duplicateOf = other.getAssignmentId();
        } else if (request.getAnswer() == AssignmentConfirmStatus.CANDIDATE) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }
        int rows = assignmentMapper.updateConfirmStatus(assignmentId, userId, request.getAnswer().name(),
                duplicateOf, request.getVersion());
        if (rows == 0) {
            throw new ConflictException(ErrorCode.VERSION_CONFLICT);
        }
        log.info("과제 여부 답: assignmentId={}, answer={}", assignmentId, request.getAnswer());
        return get(userId, assignmentId);
    }

    /**
     * 마감 설정. 「이 날짜 맞아요」도 이 경로다 — 화면이 추정값을 실어 보내므로 사용자가 무엇을
     * 확정하는지 요청에 드러난다. 저장은 항상 USER 출처다.
     */
    @Transactional
    public AssignmentResponse setDue(Long userId, Long assignmentId, AssignmentRequests.Due request) {
        getOwned(userId, assignmentId);
        DueKind kind = request.getDueKind();
        LocalDate date = null;
        LocalDateTime at = null;
        switch (kind) {
            case DATE -> {
                if (request.getDueDate() == null) {
                    throw new BadRequestException(ErrorCode.ASSIGNMENT_DUE_INVALID);
                }
                date = request.getDueDate();
            }
            case DATETIME -> {
                if (request.getDueAt() == null) {
                    throw new BadRequestException(ErrorCode.ASSIGNMENT_DUE_INVALID);
                }
                at = request.getDueAt();
            }
            default -> {
                // UNKNOWN / NONE: 둘 다 비운다. 미확인과 없음은 kind로 구분된다.
            }
        }
        int rows = assignmentMapper.updateDue(assignmentId, userId, kind.name(), date, at,
                kind == DueKind.UNKNOWN ? null : DueSource.USER.name(), request.getVersion());
        if (rows == 0) {
            throw new ConflictException(ErrorCode.VERSION_CONFLICT);
        }
        return get(userId, assignmentId);
    }

    @Transactional
    public AssignmentResponse rename(Long userId, Long assignmentId, AssignmentRequests.Rename request) {
        getOwned(userId, assignmentId);
        int rows = assignmentMapper.updateTitle(assignmentId, userId, request.getTitle().trim(), request.getVersion());
        if (rows == 0) {
            throw new ConflictException(ErrorCode.VERSION_CONFLICT);
        }
        return get(userId, assignmentId);
    }

    /** 완료 체크/해제. 그 과제를 끝냈다는 사용자 확인이고 그 이상의 의미는 없다. */
    @Transactional
    public AssignmentResponse setCompleted(Long userId, Long assignmentId, AssignmentRequests.Complete request) {
        getOwned(userId, assignmentId);
        LocalDateTime completedAt = Boolean.TRUE.equals(request.getCompleted()) ? nowLocal() : null;
        int rows = assignmentMapper.updateCompleted(assignmentId, userId, completedAt, request.getVersion());
        if (rows == 0) {
            throw new ConflictException(ErrorCode.VERSION_CONFLICT);
        }
        log.info("과제 완료 체크: assignmentId={}, completed={}", assignmentId, completedAt != null);
        return get(userId, assignmentId);
    }

    @Transactional
    public AssignmentResponse create(Long userId, AssignmentRequests.Create request) {
        if (request.getCourseId() != null) {
            courseService.getOwned(userId, request.getCourseId());
        }
        DueKind kind = request.getDueKind() == null ? DueKind.UNKNOWN : request.getDueKind();
        if (kind == DueKind.DATE && request.getDueDate() == null || kind == DueKind.DATETIME && request.getDueAt() == null) {
            throw new BadRequestException(ErrorCode.ASSIGNMENT_DUE_INVALID);
        }
        CourseAssignment assignment = CourseAssignment.builder()
                .userId(userId).courseId(request.getCourseId())
                .title(request.getTitle().trim())
                .confirmStatus(AssignmentConfirmStatus.CONFIRMED)
                .dueKind(kind)
                .dueDate(kind == DueKind.DATE ? request.getDueDate() : null)
                .dueAt(kind == DueKind.DATETIME ? request.getDueAt() : null)
                .dueSource(kind == DueKind.UNKNOWN ? null : DueSource.USER)
                .dedupeKey("manual:" + UUID.randomUUID())
                .build();
        assignmentMapper.insertIgnore(assignment);
        return get(userId, assignment.getAssignmentId());
    }

    // ===== 내부 =====

    private CourseAssignment getOwned(Long userId, Long assignmentId) {
        CourseAssignment assignment = assignmentMapper.findByIdAndUserId(assignmentId, userId);
        if (assignment == null) {
            throw new NotFoundException(ErrorCode.ASSIGNMENT_NOT_FOUND);
        }
        return assignment;
    }

    private List<AssignmentResponse> toResponses(Long userId, List<CourseAssignment> assignments) {
        if (assignments.isEmpty()) {
            return List.of();
        }
        LocalDate today = ZonedDateTime.now(clock).withZoneSameInstant(ZoneId.of(defaultTimeZoneId)).toLocalDate();
        List<Long> materialIds = assignments.stream().map(CourseAssignment::getMaterialId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, CourseMaterial> materials = materialIds.isEmpty() ? Map.of()
                : courseMaterialMapper.findByIdsAndUserIdIncludingDeleted(materialIds, userId).stream()
                .collect(Collectors.toMap(CourseMaterial::getMaterialId, m -> m, (a, b) -> a));
        List<Long> sectionIds = assignments.stream().map(CourseAssignment::getSectionId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, MaterialSection> sections = sectionIds.isEmpty() ? Map.of()
                : sectionMapper.findByIdsAndUserId(sectionIds, userId).stream()
                .collect(Collectors.toMap(MaterialSection::getSectionId, s -> s, (a, b) -> a));
        Map<Long, String> titlesById = assignments.stream()
                .collect(Collectors.toMap(CourseAssignment::getAssignmentId, CourseAssignment::getTitle, (a, b) -> a));

        List<AssignmentResponse> out = new ArrayList<>();
        for (CourseAssignment a : assignments) {
            CourseMaterial material = a.getMaterialId() == null ? null : materials.get(a.getMaterialId());
            MaterialSection section = a.getSectionId() == null ? null : sections.get(a.getSectionId());
            String duplicateTitle = a.getDuplicateOfAssignmentId() == null ? null
                    : titlesById.get(a.getDuplicateOfAssignmentId());
            if (duplicateTitle == null && a.getDuplicateOfAssignmentId() != null) {
                CourseAssignment other = assignmentMapper.findByIdAndUserId(a.getDuplicateOfAssignmentId(), userId);
                duplicateTitle = other == null ? null : other.getTitle();
            }
            out.add(AssignmentResponse.of(a, today, readEstimates(a.getDueEstimateJson()),
                    material == null ? null : material.getOriginalFilename(),
                    material != null && material.getStatus() == MaterialStatus.DELETED,
                    section == null ? null : section.locator(), duplicateTitle));
        }
        return out;
    }

    private LocalDateTime nowLocal() {
        return ZonedDateTime.now(clock).withZoneSameInstant(ZoneId.of(defaultTimeZoneId)).toLocalDateTime();
    }

    private List<Map<String, Object>> readEstimates(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, ESTIMATE_LIST);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalDate parseDate(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value);
        if (!s.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return null;
        }
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
