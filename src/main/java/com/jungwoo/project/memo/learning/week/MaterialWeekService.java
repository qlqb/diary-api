package com.jungwoo.project.memo.learning.week;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.course.CourseMapper;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.learning.week.WeekSuggestionEngine.Confidence;
import com.jungwoo.project.memo.learning.week.WeekSuggestionEngine.Placement;
import com.jungwoo.project.memo.learning.week.WeekSuggestionEngine.Suggestion;
import com.jungwoo.project.memo.learning.week.dto.MaterialWeekRequests;
import com.jungwoo.project.memo.learning.week.dto.MaterialWeekReviewResponse;
import com.jungwoo.project.memo.material.CourseMaterialMapper;
import com.jungwoo.project.memo.material.DocumentTitleReader;
import com.jungwoo.project.memo.material.FileStorageService;
import com.jungwoo.project.memo.material.MaterialLinkMapper;
import com.jungwoo.project.memo.material.MaterialSectionMapper;
import com.jungwoo.project.memo.material.analysis.MaterialAnalysisJobService;
import com.jungwoo.project.memo.material.analysis.MaterialAnalysisStatusResponse;
import com.jungwoo.project.memo.material.analysis.MaterialAnalysisStatusService;
import com.jungwoo.project.memo.material.domain.AnalysisJobKind;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.MaterialAnalysisJob;
import com.jungwoo.project.memo.material.domain.MaterialLink;
import com.jungwoo.project.memo.material.domain.MaterialSection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 자료 주차: 추천을 보여 주고, 사용자가 확인한 것만 저장한다.
 *
 * <p>규칙 셋.
 * <ul>
 *   <li><b>추천은 저장하지 않는다.</b> 근거 신호(파일명·파일 속성 제목·분석 메타·구간)는 이미 자료와 분석
 *       결과에 있다. 요청마다 {@link WeekSuggestionEngine}이 다시 계산한다 — 재분석이 끝나면 추천도 새로워지고,
 *       그래도 확정 자리는 그대로다.</li>
 *   <li><b>확정은 사용자만 한다.</b> {@code material_week_assignments}를 쓰는 곳은 이 클래스의 {@link #place}와
 *       {@link #applySuggestions} 둘뿐이고, 둘 다 사용자 요청으로만 불린다. 재분석·자동 분석·정리·학습 구조 적용은
 *       이 표를 모른다.</li>
 *   <li><b>확정이 추천을 이긴다.</b> 확정된 자료의 새 추천이 다르면 {@code suggestionDiffers}로 알리기만 한다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaterialWeekService {

    /** 기본으로 보여 줄 주차 칸. 한 학기 15주. */
    static final int DEFAULT_WEEKS = 15;

    private static final Set<String> IN_PROGRESS = Set.of("QUEUED", "RUNNING");

    private final CourseMapper courseMapper;
    private final CourseMaterialMapper courseMaterialMapper;
    private final MaterialLinkMapper materialLinkMapper;
    private final MaterialSectionMapper sectionMapper;
    private final MaterialAnalysisJobService jobService;
    private final MaterialAnalysisStatusService analysisStatusService;
    private final MaterialWeekAssignmentMapper assignmentMapper;
    private final FileStorageService fileStorageService;
    private final DocumentTitleReader documentTitleReader;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 확인 화면. 속성 제목을 아직 안 읽은 옛 자료가 있으면 여기서 한 번 읽어 채운다(이 열이 생기기 전에 올린 자료).
     */
    public MaterialWeekReviewResponse review(Long userId, Long courseId) {
        Course course = owned(userId, courseId);
        List<CourseMaterial> materials = courseMaterialMapper.findByCourseIdAndUserId(courseId, userId);
        fillMissingDocumentTitles(userId, materials);
        return build(userId, course, materials);
    }

    /**
     * 자료 하나의 자리를 정한다. 그 자료의 기존 자리는 통째로 바뀐다.
     *
     * <p>source=SUGGESTION은 "화면에 보인 추천을 그대로 확인했다"는 뜻이다. 지금 추천과 다르면 적용하지 않는다 —
     * 사용자가 보지 않은 추천을 "확인됨"으로 남기지 않는다.
     */
    @Transactional
    public MaterialWeekReviewResponse place(Long userId, Long courseId, Long materialId,
                                            MaterialWeekRequests.Place request) {
        Course course = owned(userId, courseId);
        Target target = validate(request);
        if (assignmentMapper.lockLink(userId, courseId, materialId) == null) {
            throw new NotFoundException(ErrorCode.MATERIAL_NOT_LINKED_TO_COURSE);
        }
        String source = parseSource(request.source());
        List<CourseMaterial> materials = courseMaterialMapper.findByCourseIdAndUserId(courseId, userId);
        if (MaterialWeekAssignment.SOURCE_SUGGESTION.equals(source)) {
            Suggestion current = suggestions(userId, course, materials, assignmentMapper.findActiveByCourse(userId, courseId))
                    .get(materialId);
            if (!matchesSuggestion(current, target)) {
                throw new ConflictException(ErrorCode.MATERIAL_WEEK_SUGGESTION_CHANGED);
            }
        }
        replace(userId, courseId, materialId, target, source);
        log.info("자료 주차 지정: userId={}, courseId={}, materialId={}, placement={}, weeks={}, source={}",
                userId, courseId, materialId, target.placement(), target.weeks(), source);
        return build(userId, course, materials);
    }

    /**
     * "추천대로 적용". 화면이 보여 준 추천만, 지금도 같고 한꺼번에 적용해도 되는 것(HIGH·MEDIUM)이고 아직 확인 전인
     * 자료에만 적용한다. 추천은 적용을 시작하기 전에 한 번 계산한 것을 쓴다 — 앞 자료를 적용했다고 뒤 자료의 추천이
     * 그 자리에서 바뀌어 사용자가 본 적 없는 값이 들어가지 않게.
     */
    @Transactional
    public MaterialWeekRequests.ApplyResult applySuggestions(Long userId, Long courseId,
                                                             MaterialWeekRequests.ApplySuggestions request) {
        Course course = owned(userId, courseId);
        List<MaterialWeekRequests.Expected> expected = request == null || request.items() == null
                ? List.of() : request.items();
        List<CourseMaterial> materials = courseMaterialMapper.findByCourseIdAndUserId(courseId, userId);
        Map<Long, Suggestion> suggestions = suggestions(userId, course, materials,
                assignmentMapper.findActiveByCourse(userId, courseId));
        int applied = 0;
        List<MaterialWeekRequests.Skipped> skipped = new ArrayList<>();
        for (MaterialWeekRequests.Expected e : expected) {
            if (e == null || e.materialId() == null) {
                continue;
            }
            if (assignmentMapper.lockLink(userId, courseId, e.materialId()) == null) {
                skipped.add(new MaterialWeekRequests.Skipped(e.materialId(), "NOT_FOUND"));
                continue;
            }
            if (!assignmentMapper.findByMaterial(userId, courseId, e.materialId()).isEmpty()) {
                skipped.add(new MaterialWeekRequests.Skipped(e.materialId(), "ALREADY_PLACED"));
                continue;
            }
            Suggestion s = suggestions.get(e.materialId());
            if (s == null || !s.bulkApplicable()) {
                skipped.add(new MaterialWeekRequests.Skipped(e.materialId(), "NOT_BULK"));
                continue;
            }
            if (!Objects.equals(s.placement().name(), e.placement())
                    || (s.placement() == Placement.WEEK && !Objects.equals(s.week(), e.week()))) {
                skipped.add(new MaterialWeekRequests.Skipped(e.materialId(), "CHANGED"));
                continue;
            }
            Target target = s.placement() == Placement.WEEK
                    ? new Target(Placement.WEEK, List.of(s.week())) : new Target(s.placement(), List.of());
            replace(userId, courseId, e.materialId(), target, MaterialWeekAssignment.SOURCE_SUGGESTION);
            applied++;
        }
        log.info("자료 주차 추천 적용: userId={}, courseId={}, 요청={}, 적용={}, 건너뜀={}",
                userId, courseId, expected.size(), applied, skipped.size());
        return new MaterialWeekRequests.ApplyResult(applied, skipped, build(userId, course, materials));
    }

    // ===== 조립 =====

    private MaterialWeekReviewResponse build(Long userId, Course course, List<CourseMaterial> materials) {
        List<MaterialWeekAssignment> assignments = assignmentMapper.findActiveByCourse(userId, course.getCourseId());
        Map<Long, List<MaterialWeekAssignment>> byMaterial = group(assignments);
        Map<Long, Suggestion> suggestions = suggestions(userId, course, materials, assignments);

        Map<Long, String> types = new HashMap<>();
        for (MaterialLink link : materialLinkMapper.findByCourseIdAndUserId(course.getCourseId(), userId)) {
            types.put(link.getMaterialId(), link.getMaterialType() == null ? null : link.getMaterialType().name());
        }
        Map<Long, String> states = new HashMap<>();
        for (MaterialAnalysisStatusResponse st : analysisStatusService.statuses(userId, materials)) {
            states.put(st.getMaterialId(), st.getState());
        }

        int weekCount = DEFAULT_WEEKS;
        int needsReview = 0;
        List<MaterialWeekReviewResponse.Item> items = new ArrayList<>();
        List<CourseMaterial> ordered = new ArrayList<>(materials);
        ordered.sort(Comparator.comparing(CourseMaterial::getOriginalFilename,
                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        for (CourseMaterial m : ordered) {
            MaterialWeekReviewResponse.Assignment assignment = toAssignment(byMaterial.get(m.getMaterialId()));
            Suggestion suggestion = suggestions.get(m.getMaterialId());
            String state = states.get(m.getMaterialId());
            if (assignment == null && (state == null || !IN_PROGRESS.contains(state))) {
                needsReview++;
            }
            if (assignment != null) {
                for (Integer w : assignment.weeks()) {
                    weekCount = Math.max(weekCount, w);
                }
            }
            if (suggestion != null) {
                for (WeekSuggestionEngine.Option o : suggestion.options()) {
                    if (o.week() != null) {
                        weekCount = Math.max(weekCount, o.week());
                    }
                }
            }
            items.add(new MaterialWeekReviewResponse.Item(m.getMaterialId(), m.getOriginalFilename(),
                    types.get(m.getMaterialId()), state, assignment, toResponse(suggestion),
                    differs(assignment, suggestion)));
        }
        return new MaterialWeekReviewResponse(course.getCourseId(), course.getTitle(),
                Math.min(weekCount, WeekExpressions.MAX_WEEK), needsReview, items);
    }

    /** 추천 계산. 입력은 전부 이미 저장된 것이다 — 이 메서드는 아무것도 쓰지 않는다. */
    Map<Long, Suggestion> suggestions(Long userId, Course course, List<CourseMaterial> materials,
                                      List<MaterialWeekAssignment> assignments) {
        if (materials.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = materials.stream().map(CourseMaterial::getMaterialId).toList();
        Map<Long, CourseMaterial> byId = new LinkedHashMap<>();
        materials.forEach(m -> byId.put(m.getMaterialId(), m));

        Map<Long, List<WeekSuggestionEngine.SectionInput>> sections = new HashMap<>();
        List<MaterialSection> all = new ArrayList<>(sectionMapper.findActiveByMaterialIds(ids, userId));
        all.sort(Comparator.comparing(MaterialSection::getMaterialId)
                .thenComparing(MaterialSection::getUnitStart, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(MaterialSection::getSectionId));
        for (MaterialSection s : all) {
            CourseMaterial m = byId.get(s.getMaterialId());
            // 파일이 바뀌었으면 옛 파일의 구간은 근거가 아니다.
            if (m == null || !Objects.equals(m.getFileHash(), s.getFileHash())) {
                continue;
            }
            sections.computeIfAbsent(s.getMaterialId(), k -> new ArrayList<>()).add(
                    new WeekSuggestionEngine.SectionInput(s.getSectionId(), s.getDisplayTitle(), s.getSectionLabel(),
                            roles(s.getRolesJson()), s.getExcerpt()));
        }
        Map<Long, String> contentWeeks = contentWeekLabels(userId, byId);

        List<WeekSuggestionEngine.MaterialInput> inputs = new ArrayList<>();
        for (CourseMaterial m : materials) {
            inputs.add(new WeekSuggestionEngine.MaterialInput(m.getMaterialId(), m.getOriginalFilename(),
                    m.getDocumentTitle(), contentWeeks.get(m.getMaterialId()),
                    sections.getOrDefault(m.getMaterialId(), List.of())));
        }
        Map<Long, WeekSuggestionEngine.Confirmed> confirmed = new HashMap<>();
        group(assignments).forEach((materialId, rows) -> {
            TreeSet<Integer> weeks = new TreeSet<>();
            rows.stream().filter(r -> Placement.WEEK.name().equals(r.getPlacement()))
                    .forEach(r -> weeks.add(r.getWeekNo()));
            confirmed.put(materialId, new WeekSuggestionEngine.Confirmed(
                    Placement.valueOf(rows.get(0).getPlacement()), weeks));
        });
        return WeekSuggestionEngine.suggest(course.getTitle(), inputs, confirmed);
    }

    /** 자료 분석이 읽은 "문서가 스스로 말한 주차". 지금 파일의 가장 최근 CONTENT 작업 것만. */
    private Map<Long, String> contentWeekLabels(Long userId, Map<Long, CourseMaterial> materials) {
        Map<Long, MaterialAnalysisJob> latest = new HashMap<>();
        for (MaterialAnalysisJob job : jobService.findByMaterials(userId, new ArrayList<>(materials.keySet()))) {
            CourseMaterial m = materials.get(job.getMaterialId());
            if (job.getJobKind() == AnalysisJobKind.CONTENT && m != null
                    && Objects.equals(m.getFileHash(), job.getFileHash())) {
                latest.merge(job.getMaterialId(), job, (a, b) -> a.getJobId() > b.getJobId() ? a : b);
            }
        }
        Map<Long, String> labels = new HashMap<>();
        latest.forEach((id, job) -> {
            String label = weekLabelOf(job.getCheckpointJson());
            if (label != null) {
                labels.put(id, label);
            }
        });
        return labels;
    }

    private String weekLabelOf(String checkpointJson) {
        if (checkpointJson == null || checkpointJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(checkpointJson).get("weekLabel");
            return node == null || node.isNull() ? null : node.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> roles(String rolesJson) {
        if (rolesJson == null || rolesJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(rolesJson, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 이 열이 생기기 전에 올린 PDF·PPTX의 속성 제목을 한 번 읽어 둔다. 못 읽어도 "읽었음"으로 남긴다. */
    private void fillMissingDocumentTitles(Long userId, List<CourseMaterial> materials) {
        for (CourseMaterial m : materials) {
            if (m.isDocumentTitleRead() || !DocumentTitleReader.supports(extensionOf(m))) {
                continue;
            }
            String title = null;
            try {
                Path path = fileStorageService.resolve(m.getStoragePath());
                if (Files.isReadable(path)) {
                    title = documentTitleReader.read(path, extensionOf(m));
                }
            } catch (Exception e) {
                log.info("속성 제목 채우기 건너뜀: materialId={}, error={}", m.getMaterialId(), e.getClass().getSimpleName());
            }
            courseMaterialMapper.fillDocumentTitle(m.getMaterialId(), userId, title);
            m.setDocumentTitle(title);
            m.setDocumentTitleRead(true);
        }
    }

    // ===== 쓰기 =====

    record Target(Placement placement, List<Integer> weeks) {
    }

    private void replace(Long userId, Long courseId, Long materialId, Target target, String source) {
        assignmentMapper.deleteByMaterial(userId, courseId, materialId);
        List<Integer> weeks = target.placement() == Placement.WEEK ? target.weeks() : List.of(0);
        for (Integer w : weeks) {
            assignmentMapper.insert(MaterialWeekAssignment.builder()
                    .userId(userId).courseId(courseId).materialId(materialId)
                    .placement(target.placement().name()).weekNo(w).source(source)
                    .build());
        }
    }

    static Target validate(MaterialWeekRequests.Place request) {
        if (request == null || request.placement() == null) {
            throw new BadRequestException(ErrorCode.MATERIAL_WEEK_INVALID);
        }
        Placement placement;
        try {
            placement = Placement.valueOf(request.placement().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(ErrorCode.MATERIAL_WEEK_INVALID);
        }
        List<Integer> weeks = request.weeks() == null ? List.of() : request.weeks();
        if (placement != Placement.WEEK) {
            if (!weeks.isEmpty()) {
                throw new BadRequestException(ErrorCode.MATERIAL_WEEK_INVALID, "전체 참고·주차 없음에는 주차를 적지 않아요");
            }
            return new Target(placement, List.of());
        }
        TreeSet<Integer> distinct = new TreeSet<>();
        for (Integer w : weeks) {
            if (w == null || w < 1 || w > WeekExpressions.MAX_WEEK) {
                throw new BadRequestException(ErrorCode.MATERIAL_WEEK_INVALID,
                        "주차는 1~" + WeekExpressions.MAX_WEEK + " 사이여야 해요");
            }
            distinct.add(w);
        }
        if (distinct.isEmpty()) {
            throw new BadRequestException(ErrorCode.MATERIAL_WEEK_INVALID, "주차를 하나 이상 골라 주세요");
        }
        return new Target(Placement.WEEK, List.copyOf(distinct));
    }

    private static String parseSource(String source) {
        if (MaterialWeekAssignment.SOURCE_SUGGESTION.equals(source)) {
            return MaterialWeekAssignment.SOURCE_SUGGESTION;
        }
        if (source == null || MaterialWeekAssignment.SOURCE_USER.equals(source)) {
            return MaterialWeekAssignment.SOURCE_USER;
        }
        throw new BadRequestException(ErrorCode.MATERIAL_WEEK_INVALID);
    }

    /** 확인한 자리가 지금 추천(충돌이면 그 후보 중 하나)과 같은가. */
    static boolean matchesSuggestion(Suggestion s, Target target) {
        if (s == null) {
            return false;
        }
        if (target.placement() == Placement.WEEK && target.weeks().size() != 1) {
            return false;
        }
        Integer week = target.placement() == Placement.WEEK ? target.weeks().get(0) : null;
        return s.options().stream().anyMatch(o -> o.placement() == target.placement() && Objects.equals(o.week(), week));
    }

    // ===== 변환 =====

    private static Map<Long, List<MaterialWeekAssignment>> group(List<MaterialWeekAssignment> rows) {
        Map<Long, List<MaterialWeekAssignment>> byMaterial = new LinkedHashMap<>();
        for (MaterialWeekAssignment a : rows) {
            byMaterial.computeIfAbsent(a.getMaterialId(), k -> new ArrayList<>()).add(a);
        }
        return byMaterial;
    }

    static MaterialWeekReviewResponse.Assignment toAssignment(List<MaterialWeekAssignment> rows) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        String placement = rows.get(0).getPlacement();
        List<Integer> weeks = Placement.WEEK.name().equals(placement)
                ? rows.stream().map(MaterialWeekAssignment::getWeekNo).sorted().toList() : List.of();
        // 한 자료의 행들은 한 번의 요청으로 함께 쓰인다 — 출처도 같다. 그래도 섞였다면 직접 지정을 앞세운다.
        String source = rows.stream().anyMatch(r -> MaterialWeekAssignment.SOURCE_USER.equals(r.getSource()))
                ? MaterialWeekAssignment.SOURCE_USER : MaterialWeekAssignment.SOURCE_SUGGESTION;
        return new MaterialWeekReviewResponse.Assignment(placement, weeks, source);
    }

    private static MaterialWeekReviewResponse.Suggestion toResponse(Suggestion s) {
        if (s == null) {
            return null;
        }
        return new MaterialWeekReviewResponse.Suggestion(s.placement() == null ? null : s.placement().name(), s.week(),
                s.confidence().name(), s.bulkApplicable(),
                s.options().stream().map(o -> new MaterialWeekReviewResponse.Option(o.placement().name(), o.week())).toList(),
                s.signals().stream().map(sig -> new MaterialWeekReviewResponse.Evidence(sig.kind().name(), sig.detail()))
                        .toList());
    }

    /**
     * 확정 자리가 있는데 새 추천이 그 자리를 벗어나는가. 약한 추천(LOW)은 알리지 않는다 — 사용자가 이미 정한 것을
     * 흔들 만한 근거가 아니다. "주차 없음"으로 확인한 자료도 다시 묻지 않는다.
     */
    static boolean differs(MaterialWeekReviewResponse.Assignment assignment, Suggestion s) {
        if (assignment == null || s == null || s.confidence() == Confidence.LOW
                || Placement.UNASSIGNED.name().equals(assignment.placement())) {
            return false;
        }
        return s.options().stream().noneMatch(o -> o.placement().name().equals(assignment.placement())
                && (o.placement() != Placement.WEEK || assignment.weeks().contains(o.week())));
    }

    private Course owned(Long userId, Long courseId) {
        List<Course> owned = courseMapper.findByIdsAndUserId(List.of(courseId), userId);
        if (owned == null || owned.isEmpty()) {
            throw new NotFoundException(ErrorCode.COURSE_NOT_FOUND);
        }
        return owned.get(0);
    }

    private static String extensionOf(CourseMaterial material) {
        String name = material.getStoredFilename() != null ? material.getStoredFilename() : material.getOriginalFilename();
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
