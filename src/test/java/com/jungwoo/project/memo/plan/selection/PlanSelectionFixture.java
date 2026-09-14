package com.jungwoo.project.memo.plan.selection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.ai.AiConsultationClient;
import com.jungwoo.project.memo.ai.AiStreamParser;
import com.jungwoo.project.memo.ai.AiUsageLimitService;
import com.jungwoo.project.memo.ai.UserContextMapper;
import com.jungwoo.project.memo.assignment.CourseAssignmentService;
import com.jungwoo.project.memo.assignment.domain.AssignmentConfirmStatus;
import com.jungwoo.project.memo.assignment.domain.CourseAssignment;
import com.jungwoo.project.memo.assignment.domain.DueKind;
import com.jungwoo.project.memo.assignment.domain.DueSource;
import com.jungwoo.project.memo.course.CourseMapper;
import com.jungwoo.project.memo.course.CourseNoteMapper;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.execution.ExecutionItemMapper;
import com.jungwoo.project.memo.learning.TopicMaterialLinkMapper;
import com.jungwoo.project.memo.learning.TopicService;
import com.jungwoo.project.memo.learning.domain.TopicLinkOrigin;
import com.jungwoo.project.memo.learning.domain.TopicMaterialLink;
import com.jungwoo.project.memo.learning.domain.TopicProgressStatus;
import com.jungwoo.project.memo.learning.domain.TopicUserMark;
import com.jungwoo.project.memo.learning.dto.TopicResponse;
import com.jungwoo.project.memo.material.CourseMaterialAnalysisMapper;
import com.jungwoo.project.memo.material.CourseMaterialMapper;
import com.jungwoo.project.memo.material.MaterialLinkMapper;
import com.jungwoo.project.memo.material.MaterialSectionMapper;
import com.jungwoo.project.memo.material.MaterialTextUnitMapper;
import com.jungwoo.project.memo.material.analysis.MaterialAnalysisJobService;
import com.jungwoo.project.memo.material.domain.AnalysisJobKind;
import com.jungwoo.project.memo.material.domain.AnalysisJobStatus;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.ExtractionStatus;
import com.jungwoo.project.memo.material.domain.MaterialAnalysisJob;
import com.jungwoo.project.memo.material.domain.MaterialLink;
import com.jungwoo.project.memo.material.domain.MaterialSection;
import com.jungwoo.project.memo.material.domain.MaterialStatus;
import com.jungwoo.project.memo.material.domain.MaterialTextUnit;
import com.jungwoo.project.memo.material.domain.MaterialType;
import com.jungwoo.project.memo.material.domain.TextUnitType;
import com.jungwoo.project.memo.plan.PeriodPlanDraftGenerator;
import com.jungwoo.project.memo.plan.PlanMaterialContextService;
import com.jungwoo.project.memo.plan.PlanReviewService;
import com.jungwoo.project.memo.plan.domain.PlanIntensity;
import com.jungwoo.project.memo.scheduling.domain.AvailabilityConfidence;
import com.jungwoo.project.memo.scheduling.domain.AvailabilitySource;
import com.jungwoo.project.memo.scheduling.domain.AvailabilityWindow;
import com.jungwoo.project.memo.scheduling.service.AvailabilityEstimateResult;
import com.jungwoo.project.memo.scheduling.service.AvailabilityEstimateService;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 자료 선택 흐름을 <b>실제 코드</b>(카탈로그·선택기·조회기·지정 자료 해석·생성기)로 돌리고, DB만 메모리 목록으로 바꾼 틀.
 *
 * <p>모델은 두 종류의 호출을 시스템 프롬프트로 구분해 답한다: 선택 호출은 테스트가 준 함수(프롬프트를 보고 핸들을
 * 고른다), 계획 호출은 고정 JSON. 모든 호출의 (시스템, 사용자) 프롬프트를 남겨 "선택 결과가 계획 입력에 실제로
 * 들어갔는가"를 문자열로 확인한다.
 */
public class PlanSelectionFixture {

    public static final long USER = 7L;
    public static final long OTHER_USER = 8L;
    public static final LocalDate START = LocalDate.of(2026, 9, 14);
    public static final LocalDate END = LocalDate.of(2026, 9, 20);

    public final AiConsultationClient ai = mock(AiConsultationClient.class);
    public final AiUsageLimitService usage = mock(AiUsageLimitService.class);
    public final CourseMapper courseMapper = mock(CourseMapper.class);
    public final TopicService topicService = mock(TopicService.class);
    public final TopicMaterialLinkMapper topicLinkMapper = mock(TopicMaterialLinkMapper.class);
    public final MaterialSectionMapper sectionMapper = mock(MaterialSectionMapper.class);
    public final CourseMaterialMapper courseMaterialMapper = mock(CourseMaterialMapper.class);
    public final CourseAssignmentService assignmentService = mock(CourseAssignmentService.class);
    public final MaterialAnalysisJobService jobService = mock(MaterialAnalysisJobService.class);
    public final MaterialTextUnitMapper unitMapper = mock(MaterialTextUnitMapper.class);
    public final MaterialLinkMapper materialLinkMapper = mock(MaterialLinkMapper.class);
    public final UserContextMapper userContextMapper = mock(UserContextMapper.class);

    public final Map<Long, Course> courses = new LinkedHashMap<>();
    public final Map<Long, List<TopicResponse>> trees = new LinkedHashMap<>();
    public final Map<Long, CourseMaterial> materials = new LinkedHashMap<>();
    public final List<MaterialLink> materialLinks = new ArrayList<>();
    public final List<MaterialSection> sections = new ArrayList<>();
    public final List<TopicMaterialLink> topicLinks = new ArrayList<>();
    public final List<CourseAssignment> assignments = new ArrayList<>();
    public final List<MaterialTextUnit> units = new ArrayList<>();

    /** 선택 호출의 답. 사용자 프롬프트를 받아 구조화 JSON을 돌려준다. null이면 빈 선택. */
    public Function<String, String> selectionAnswer = prompt -> emptySelection();
    public String planJson = planWithOneItem("s1");
    /** 선택 호출을 실패시키려면 여기에 예외를 둔다. */
    public RuntimeException selectionError;

    public final List<String[]> calls = new ArrayList<>();

    public final PlanMaterialSelector selector;
    public final PlanMaterialRetriever retriever;
    public final PlanRequestedMaterialResolver resolver;
    public final PlanMaterialContextService catalogService;
    public final PeriodPlanDraftGenerator generator;

    public PlanSelectionFixture() {
        PromptTokenEstimator estimator = new PromptTokenEstimator(0.10);
        selector = new PlanMaterialSelector(ai, usage, estimator);
        retriever = new PlanMaterialRetriever(sectionMapper, courseMaterialMapper, unitMapper, materialLinkMapper);
        resolver = new PlanRequestedMaterialResolver(courseMaterialMapper, materialLinkMapper, sectionMapper);
        catalogService = new PlanMaterialContextService(topicService, topicLinkMapper, sectionMapper, courseMaterialMapper,
                assignmentService, jobService, new ObjectMapper());

        ExecutionItemMapper executionItemMapper = mock(ExecutionItemMapper.class);
        PlanReviewService planReviewService = mock(PlanReviewService.class);
        CourseNoteMapper courseNoteMapper = mock(CourseNoteMapper.class);
        CourseMaterialAnalysisMapper analysisMapper = mock(CourseMaterialAnalysisMapper.class);
        AvailabilityEstimateService availability = mock(AvailabilityEstimateService.class);
        generator = new PeriodPlanDraftGenerator(ai, usage, planReviewService, courseMapper, topicService,
                courseNoteMapper, analysisMapper, courseMaterialMapper, executionItemMapper, availability,
                Clock.fixed(Instant.parse("2026-09-13T09:00:00Z"), ZoneId.of("UTC")), catalogService,
                userContextMapper, selector, retriever, resolver, estimator);
        ReflectionTestUtils.setField(generator, "maxCompletionTokens", 2000);
        ReflectionTestUtils.setField(generator, "requestTimeoutSeconds", 90);
        ReflectionTestUtils.setField(generator, "modelName", "test-model");
        ReflectionTestUtils.setField(generator, "defaultTimeZoneId", "Asia/Seoul");

        when(ai.isConfigured()).thenReturn(true);
        when(ai.streamTurn(any(), any(), anyInt())).thenAnswer(inv -> {
            String system = inv.getArgument(0);
            String user = inv.getArgument(1);
            calls.add(new String[]{system, user});
            if (isSelection(system)) {
                if (selectionError != null) {
                    return Flux.error(selectionError);
                }
                return structured(selectionAnswer.apply(user));
            }
            return structured(planJson);
        });
        when(executionItemMapper.findByUserIdAndPlanningRange(anyLong(), any(), any())).thenReturn(List.of());
        when(planReviewService.summarizeLatestForPrompt(anyLong())).thenReturn(null);
        when(analysisMapper.findAppliedByCourseIdAndUserId(anyLong(), anyLong())).thenReturn(List.of());
        when(courseNoteMapper.findByCourseIdAndUserId(anyLong(), anyLong())).thenReturn(List.of());
        when(userContextMapper.findActiveAndStaleByUserId(anyLong(), anyInt())).thenReturn(List.of());
        List<AvailabilityWindow> windows = List.of(new AvailabilityWindow(START.atTime(9, 0),
                START.atTime(9, 0).plusMinutes(925), AvailabilitySource.DEFAULT_INFERENCE, AvailabilityConfidence.LOW,
                "기본 시간대"));
        when(availability.estimate(anyLong(), any(), any(), any(), any()))
                .thenReturn(new AvailabilityEstimateResult(windows, List.of()));

        when(courseMapper.findByUserIdAndStatus(anyLong(), any())).thenAnswer(inv -> new ArrayList<>(courses.values()));
        when(courseMapper.findByIdsAndUserId(any(), anyLong())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            return courses.values().stream().filter(c -> ids.contains(c.getCourseId())).toList();
        });
        when(topicService.getTopicTree(anyLong(), anyLong()))
                .thenAnswer(inv -> trees.getOrDefault((Long) inv.getArgument(1), List.of()));
        when(topicLinkMapper.findActiveByCourseId(anyLong(), anyLong())).thenAnswer(inv -> topicLinks.stream()
                .filter(l -> Objects.equals(l.getCourseId(), inv.getArgument(0))
                        && Objects.equals(l.getUserId(), inv.getArgument(1))).toList());
        when(sectionMapper.findActiveByMaterialIds(any(), anyLong())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            Long userId = inv.getArgument(1);
            return sections.stream().filter(s -> ids.contains(s.getMaterialId()) && Objects.equals(s.getUserId(), userId)
                    && "ACTIVE".equals(s.getStatus())).toList();
        });
        when(sectionMapper.findByIdsAndUserId(any(), anyLong())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            Long userId = inv.getArgument(1);
            return sections.stream().filter(s -> ids.contains(s.getSectionId()) && Objects.equals(s.getUserId(), userId))
                    .toList();
        });
        when(sectionMapper.findByIdAndUserId(anyLong(), anyLong())).thenAnswer(inv -> sections.stream()
                .filter(s -> Objects.equals(s.getSectionId(), inv.getArgument(0))
                        && Objects.equals(s.getUserId(), inv.getArgument(1))).findFirst().orElse(null));
        when(courseMaterialMapper.findByCourseIdAndUserId(anyLong(), anyLong())).thenAnswer(inv -> {
            Long courseId = inv.getArgument(0);
            Long userId = inv.getArgument(1);
            Set<Long> linked = new HashSet<>();
            materialLinks.stream().filter(l -> Objects.equals(l.getCourseId(), courseId)).forEach(l -> linked.add(l.getMaterialId()));
            return materials.values().stream().filter(m -> linked.contains(m.getMaterialId())
                    && Objects.equals(m.getUserId(), userId) && m.getStatus() == MaterialStatus.ACTIVE).toList();
        });
        when(courseMaterialMapper.findByIdAndUserId(anyLong(), anyLong())).thenAnswer(inv -> {
            CourseMaterial m = materials.get((Long) inv.getArgument(0));
            return m != null && Objects.equals(m.getUserId(), inv.getArgument(1)) && m.getStatus() == MaterialStatus.ACTIVE
                    ? m : null;
        });
        when(courseMaterialMapper.findByIdsAndUserIdIncludingDeleted(any(), anyLong())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            return materials.values().stream().filter(m -> ids.contains(m.getMaterialId())
                    && Objects.equals(m.getUserId(), inv.getArgument(1))).toList();
        });
        when(assignmentService.findByCourses(anyLong(), any())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(1);
            return assignments.stream().filter(a -> ids.contains(a.getCourseId())
                    && Objects.equals(a.getUserId(), inv.getArgument(0))).toList();
        });
        when(jobService.findByMaterials(anyLong(), any())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(1);
            long[] id = {1};
            return materials.values().stream().filter(m -> ids.contains(m.getMaterialId()))
                    .map(m -> MaterialAnalysisJob.builder().jobId(id[0]++).materialId(m.getMaterialId())
                            .jobKind(AnalysisJobKind.CONTENT).status(AnalysisJobStatus.DONE).fileHash(m.getFileHash()).build())
                    .toList();
        });
        when(unitMapper.findByMaterialIdAndHash(anyLong(), any())).thenAnswer(inv -> units.stream()
                .filter(u -> Objects.equals(u.getMaterialId(), inv.getArgument(0))
                        && Objects.equals(u.getFileHash(), inv.getArgument(1))).toList());
        when(materialLinkMapper.findByMaterialIdAndCourseIdAndUserId(anyLong(), anyLong(), anyLong())).thenAnswer(inv ->
                materialLinks.stream().filter(l -> Objects.equals(l.getMaterialId(), inv.getArgument(0))
                        && Objects.equals(l.getCourseId(), inv.getArgument(1))
                        && Objects.equals(l.getUserId(), inv.getArgument(2))).findFirst().orElse(null));
        when(materialLinkMapper.findByMaterialIdAndUserId(anyLong(), anyLong())).thenAnswer(inv ->
                materialLinks.stream().filter(l -> Objects.equals(l.getMaterialId(), inv.getArgument(0))
                        && Objects.equals(l.getUserId(), inv.getArgument(1))).toList());
    }

    // ===== 데이터 =====

    public Course course(long id, String title) {
        Course c = Course.builder().courseId(id).userId(USER).title(title).build();
        courses.put(id, c);
        trees.putIfAbsent(id, new ArrayList<>());
        return c;
    }

    public TopicResponse topic(long courseId, long id, Long parentId, String title, String locator,
                               TopicProgressStatus progress) {
        return topic(courseId, id, parentId, title, locator, progress, null);
    }

    public TopicResponse topic(long courseId, long id, Long parentId, String title, String locator,
                               TopicProgressStatus progress, TopicUserMark mark) {
        TopicResponse t = TopicResponse.builder().topicId(id).parentTopicId(parentId).title(title).sourceLocator(locator)
                .progressStatus(progress).userMark(mark).children(new ArrayList<>()).build();
        if (parentId == null) {
            trees.get(courseId).add(t);
        } else {
            find(trees.get(courseId), parentId).getChildren().add(t);
        }
        return t;
    }

    public TopicResponse topic(long courseId, long id, String title) {
        return topic(courseId, id, null, title, null, TopicProgressStatus.NOT_STARTED);
    }

    private static TopicResponse find(List<TopicResponse> nodes, long id) {
        for (TopicResponse n : nodes) {
            if (n.getTopicId() == id) {
                return n;
            }
            TopicResponse inner = find(n.getChildren(), id);
            if (inner != null) {
                return inner;
            }
        }
        return null;
    }

    public CourseMaterial material(long id, String filename, Long... courseIds) {
        return material(USER, id, filename, courseIds);
    }

    public CourseMaterial material(long userId, long id, String filename, Long... courseIds) {
        CourseMaterial m = CourseMaterial.builder().materialId(id).userId(userId).originalFilename(filename)
                .fileHash("h" + id).status(MaterialStatus.ACTIVE).extractionStatus(ExtractionStatus.SUCCESS).build();
        materials.put(id, m);
        for (Long courseId : courseIds) {
            materialLinks.add(MaterialLink.builder().userId(userId).materialId(id).courseId(courseId)
                    .materialType(MaterialType.PROFESSOR_SLIDE).build());
        }
        return m;
    }

    /** 구간 + 그 범위의 원문 단위. 원문에는 "원문 {id}"라는 고유 문장을 넣는다 — 계획 입력에 실렸는지 이 문장으로 본다. */
    public MaterialSection section(long id, long materialId, String title, String roles, int page, String bodyMarker) {
        CourseMaterial m = materials.get(materialId);
        MaterialSection s = MaterialSection.builder().sectionId(id).userId(m.getUserId()).materialId(materialId)
                .fileHash(m.getFileHash()).analysisVersion(1).chunkIndex(0).unitType(TextUnitType.PDF_PAGE)
                .unitStart(page).unitEnd(page).displayTitle(title).rolesJson(roles)
                .excerpt("목록 설명 " + id).status("ACTIVE").dedupeKey("k" + id).build();
        sections.add(s);
        units.add(MaterialTextUnit.builder().unitId(id * 10).userId(m.getUserId()).materialId(materialId)
                .fileHash(m.getFileHash()).unitIndex(page).unitType(TextUnitType.PDF_PAGE).unitNo(page)
                .text(bodyMarker).build());
        return s;
    }

    public void link(long courseId, long topicId, long sectionId) {
        MaterialSection s = sections.stream().filter(x -> x.getSectionId() == sectionId).findFirst().orElseThrow();
        topicLinks.add(TopicMaterialLink.builder().userId(USER).courseId(courseId).topicId(topicId)
                .materialId(s.getMaterialId()).sectionId(sectionId).origin(TopicLinkOrigin.PROPOSAL_APPLIED)
                .status("ACTIVE").build());
    }

    public CourseAssignment assignment(long id, long courseId, Long topicId, Long sectionId, String title,
                                       LocalDate due, boolean completed) {
        MaterialSection s = sectionId == null ? null
                : sections.stream().filter(x -> x.getSectionId() == sectionId.longValue()).findFirst().orElseThrow();
        CourseAssignment a = CourseAssignment.builder().assignmentId(id).userId(USER).courseId(courseId).topicId(topicId)
                .sectionId(sectionId).materialId(s == null ? null : s.getMaterialId()).title(title)
                .confirmStatus(AssignmentConfirmStatus.CONFIRMED)
                .dueKind(due == null ? DueKind.NONE : DueKind.DATE).dueDate(due)
                .dueSource(due == null ? null : DueSource.SOURCE)
                .completedAt(completed ? java.time.LocalDateTime.of(2026, 9, 12, 20, 0) : null)
                .version(1L).build();
        assignments.add(a);
        return a;
    }

    // ===== 실행 =====

    public PeriodPlanDraftGenerator.Generated generate(String instruction) {
        return generate(instruction, List.of(), List.of(), List.of());
    }

    public PeriodPlanDraftGenerator.Generated generate(String instruction, List<Long> courseIds,
                                                        List<Long> excludeTopicIds, List<Long> requestedMaterialIds) {
        return generator.generate(new PeriodPlanDraftGenerator.Spec(USER, START, END, PlanIntensity.NORMAL, instruction,
                null, courseIds, excludeTopicIds, requestedMaterialIds, List.of()));
    }

    public List<String> selectionPrompts() {
        return calls.stream().filter(c -> isSelection(c[0])).map(c -> c[1]).toList();
    }

    public List<String> planPrompts() {
        return calls.stream().filter(c -> !isSelection(c[0])).map(c -> c[1]).toList();
    }

    public String planPrompt() {
        List<String> plans = planPrompts();
        return plans.isEmpty() ? null : plans.get(plans.size() - 1);
    }

    // ===== 모델 응답 =====

    public static boolean isSelection(String system) {
        return system != null && system.startsWith(PlanMaterialSelector.SYSTEM_PROMPT.substring(0, 40));
    }

    public static Flux<ChatResponse> structured(String json) {
        return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(
                "골랐어요.\n" + AiStreamParser.DELIMITER + "\n" + json)))));
    }

    public static String emptySelection() {
        return "{\"selectedSectionIds\":[],\"selectedTopicIds\":[],\"expandGroupIds\":[],\"reasons\":[],"
                + "\"insufficientEvidence\":false,\"note\":null}";
    }

    public static String selection(List<String> sections, List<String> topics, List<String> expand) {
        StringBuilder reasons = new StringBuilder("[");
        List<String> all = new ArrayList<>(sections);
        all.addAll(topics);
        for (int i = 0; i < all.size(); i++) {
            reasons.append(i == 0 ? "" : ",").append("{\"id\":\"").append(all.get(i)).append("\",\"reason\":\"이유 ")
                    .append(all.get(i)).append("\"}");
        }
        reasons.append("]");
        return "{\"selectedSectionIds\":" + jsonList(sections) + ",\"selectedTopicIds\":" + jsonList(topics)
                + ",\"expandGroupIds\":" + jsonList(expand) + ",\"reasons\":" + reasons
                + ",\"insufficientEvidence\":false,\"note\":null}";
    }

    private static String jsonList(List<String> values) {
        return "[" + String.join(",", values.stream().map(v -> "\"" + v + "\"").toList()) + "]";
    }

    public static String planWithOneItem(String refId) {
        return "{\"title\":\"이번 주\",\"goalSummary\":null,\"items\":[{\"title\":\"자료구조 · 한 조각\","
                + "\"description\":\"한다 · 완료: 끝\",\"expectedMinutes\":30,\"priority\":\"SHOULD\",\"courseId\":null,"
                + "\"scheduledDate\":null,\"reason\":\"이유\",\"refIds\":[\"" + refId + "\"]}]}";
    }

    /** 프롬프트에서 글귀가 들어 있는 줄의 핸들(t12/m7/g3/c1)을 찾는다. 테스트가 번호를 외우지 않게 한다. */
    public static String handleOf(String prompt, String fragment) {
        Pattern handle = Pattern.compile("(?<![a-z0-9])([tmgc]\\d+)\\s");
        for (String line : prompt.split("\n")) {
            if (line.contains(fragment)) {
                Matcher m = handle.matcher(line);
                if (m.find()) {
                    return m.group(1);
                }
            }
        }
        return null;
    }

    /** 계획 프롬프트에서 글귀가 들어 있는 줄의 인용 번호([s12]). */
    public static String refOf(String prompt, String fragment) {
        Pattern ref = Pattern.compile("\\[(s\\d+)]\\s*$");
        for (String line : prompt.split("\n")) {
            if (line.contains(fragment)) {
                Matcher m = ref.matcher(line);
                if (m.find()) {
                    return m.group(1);
                }
            }
        }
        return null;
    }
}
