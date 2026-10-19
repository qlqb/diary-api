package com.jungwoo.project.memo.plan.scope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.ai.AiChatResponseUtils;
import com.jungwoo.project.memo.ai.AiConsultationClient;
import com.jungwoo.project.memo.ai.AiStreamParser;
import com.jungwoo.project.memo.ai.AiUsageLimitService;
import com.jungwoo.project.memo.ai.brief.PlanBriefService;
import com.jungwoo.project.memo.ai.domain.UsageResultStatus;
import com.jungwoo.project.memo.material.analysis.ModelJson;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.plan.PlanMaterialContextService.CourseCatalog;
import com.jungwoo.project.memo.plan.selection.PromptTokenEstimator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 계획 생성 ① — 사용자 원문에서 이번 요청의 과목별 범위를 뽑는다(SCOPE 호출). 자료를 고르거나 계획을 만들지 않는다.
 *
 * <p>범위와 자료 선택을 같은 응답에서 받으면 둘이 함께 빠질 때 서버가 잡지 못한다. 그래서 선택보다 먼저 따로 부르고, 서버가 검증한
 * 결과를 선택·계획의 입력으로만 넘긴다(이후 호출은 범위를 지우거나 id를 다시 매기지 못한다).
 *
 * <p>서버 검증: 과목·자료 핸들은 이번에 보여 준 것만(같은 사용자의 대상 과목 자료), 근거 구절은 이번 요청의 사용자 원문에 그대로
 * 있어야 한다. 확인하지 못한 범위는 지우지 않고 {@code verified=false}로 남긴다. 호출이 실패하면 범위 없이 계속한다(이전과 같은 동작).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlanScopeExtractor {

    public static final String FEATURE = "PLAN_SCOPE_EXTRACTION";
    static final int MAX_SCOPES = 12;
    static final int MAX_PARTS = 12;
    static final int MAX_MATERIALS_PER_COURSE = 60;
    static final int MAX_LABEL_CHARS = 80;
    static final int MAX_GOAL_CHARS = 160;

    private final AiConsultationClient aiConsultationClient;
    private final AiUsageLimitService aiUsageLimitService;
    private final PromptTokenEstimator estimator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${plan.scope.max-completion-tokens:3000}")
    private int maxCompletionTokens = 3000;

    @Value("${ai.request.timeout-seconds:90}")
    private int requestTimeoutSeconds = 90;

    @Value("${spring.ai.openai.chat.model:gpt-5.6-luna}")
    private String modelName = "gpt-5.6-luna";

    public static final String SYSTEM_PROMPT = """
            너는 학습 계획을 만들기 직전에, 사용자가 이번 요청에서 말한 범위를 과목별로 그대로 옮겨 적는 조수다.
            계획을 만들거나 자료를 고르지 않는다. 사용자가 말하지 않은 범위를 지어내지 않는다.

            규칙:
            - scope 하나 = 과목 하나의 요청 하나. quote에는 그 근거가 된 사용자 원문 구절을 한 글자도 바꾸지 말고 그대로 적는다.
            - role: "끝내고 싶어", "해야 돼", "공부할 거야"처럼 할 일로 말했으면 REQUIRED, "가능하면", "시간 남으면"이면 OPTIONAL.
            - goal: 무엇을 할 수 있게 되려는지(예 "배포 PDF만 보고 xml 속성과 코드 빈칸을 채운다", "교재에 표시한 범위를 덮고 답한다").
              사용자가 시험 방식·준비 방식을 말했으면 그것을 goal에 남긴다. 말하지 않았으면 "시험 범위 학습".
            - boundary: 시작·끝·제외를 원문 그대로 요약(예 "붓꽃·타이타닉까지", "2·3·6·7주차, 7주차는 56번 장표까지, AWS 서비스 암기·설치 절차 제외").
            - parts: 그 범위를 독립적으로 확인할 수 있는 단위로 나눈다. 주차·장·주제가 여럿이면 하나씩("2주차", "3주차"…,
              "Navigation", "RecyclerView", "SQLite", "이벤트 처리").
              · "…까지"처럼 끝만 말하고 시작을 말하지 않았으면, 그 과목의 첫 학습 자료부터 끝 자료까지를 누적 범위로 보고 자료마다
                part를 만든다(강의계획서·과목 소개처럼 학습 본문이 아닌 자료는 넣지 않는다). 자료 목록은 올린 순서다 — 파일명의
                주차·번호와 강의계획서의 주차 순서로 수업 순서를 판단하고, 번호가 없는 자료도 내용(예 "파이썬 기초"는 넘파이 앞)으로
                자리를 정한다. 순서를 정할 수 없으면 part 하나를 "경계 미확인: …"으로 둔다.
              · materials: 그 부분을 다루는 자료의 핸들(d번호)을 아래 목록에서만 고른다. 파일명에 버전·복사 표시(_v5, (1))가
                붙어 있어도 같은 주차·제목이면 그 자료다. 같은 주차에 판이 여럿이면 모두 적는다(최신을 추정해 하나만 고르지 않는다).
              · 목록에 그 부분의 자료가 없으면 materials는 []이고 noMaterial=true.
              · 사용자가 따로 가진 자료로 한다고 말했으면(예 "책에 표시해 놨어", "시험 때 주는 PDF") userMaterial=true.
            - 과목을 알 수 없는 범위는 course를 null로 둔다.
            - studyTime: 사용자가 이번 요청에서 쓸 수 있는 시간·시간대를 말했으면 적는다(예 "4시까지 공부가능해" → until "16:00",
              "오늘 한 시간만" → minutes 60·per "DAY"). 여러 번 말했으면 가장 최근 발언 하나만. 그 발언의 분량과 시간대를 모두 적는다.
              오전·오후를 말하지 않은 시각은 [시각 기준] 이후의 가장 이른 시각으로 본다. 밤 12시·자정은 "24:00". quote에는 그 시간을
              말한 사용자 원문 구절을 그대로. 말하지 않았으면 null.

            응답 형식(반드시 지킨다):
            1) 뽑은 범위를 한 문장으로.
            2) 다음 줄에 정확히: %s
            3) 그 아래 JSON 객체 하나:
            {"scopes":[{"course":"c1"|null,"role":"REQUIRED"|"OPTIONAL","goal":"…","boundary":"…","quote":"사용자 원문 구절",
              "parts":[{"label":"…","materials":["d3"],"noMaterial":false,"userMaterial":false}]}],
             "studyTime":{"minutes":정수|null,"per":"DAY"|"PLAN","from":"HH:mm"|null,"until":"HH:mm"|null,"quote":"사용자 원문 구절"}|null}
            사용자가 범위를 하나도 말하지 않았으면 "scopes":[].
            """.formatted(AiStreamParser.DELIMITER);

    /**
     * @param instruction  이번 요청의 사용자 원문(서버가 사용자 메시지로 만든 지시문). 근거 구절은 여기서만 확인한다
     * @param agreedLines  유효한 상담 합의(참고용). 근거로 쓰지 않는다
     * @param timeReference 오전·오후를 말하지 않은 시각을 정하는 기준(계획 첫날의 남는 시간이 시작하는 시각). 없으면 null —
     *                      그때 애매한 시각은 확인된 시간이 되지 못한다
     */
    /** 대화에서 계획을 요청할 때 서버가 만드는 지시문의 머리말. 이 뒤로 발언마다 "- "로 시작한다. */
    public static final String CONVERSATION_HEADER = "아래는 이 계획을 요청하기까지 사용자가 대화에서 말한 것이다(오래된 것부터). ";

    /**
     * 대화 지시문에 사용자 메시지 하나를 붙인다. 첫 줄은 "- ", 이어지는 줄은 두 칸 들여 쓴다 — 메시지 안의 "- " 줄이 발언 경계로
     * 읽히지 않게. {@link #utteranceLines}가 같은 규칙으로 되읽는다.
     */
    public static void appendUtterance(StringBuilder sb, String message) {
        String[] lines = message.strip().split("\\R");
        sb.append("- ").append(lines[0].strip()).append('\n');
        for (int i = 1; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                sb.append("  ").append(lines[i].strip()).append('\n');
            }
        }
    }

    public record Request(Long userId, String workflowId, String instruction, List<String> agreedLines,
                          List<CourseCatalog> catalogs, java.time.LocalTime timeReference) {
        public Request(Long userId, String workflowId, String instruction, List<String> agreedLines,
                       List<CourseCatalog> catalogs) {
            this(userId, workflowId, instruction, agreedLines, catalogs, null);
        }
    }

    /** @param prompt 사용자 프롬프트(생성 기록용). 호출하지 않았으면 null */
    public record Result(String status, List<RequestScope> scopes, String systemPrompt, String prompt,
                         Integer estimatedTokens, String note,
                         /** 상한을 넘거나 읽을 수 없는 원소가 있어 버린 범위·부분이 있다 */
                         boolean incomplete,
                         /** 원문과 대조해 확인한 쓸 수 있는 시간. 말하지 않았거나 확인하지 못했으면 null */
                         ScopeReport.StudyTime studyTime) {
        public Result(String status, List<RequestScope> scopes, String systemPrompt, String prompt, Integer estimatedTokens,
                      String note) {
            this(status, scopes, systemPrompt, prompt, estimatedTokens, note, false, null);
        }

        public Result(String status, List<RequestScope> scopes, String systemPrompt, String prompt, Integer estimatedTokens,
                      String note, boolean incomplete) {
            this(status, scopes, systemPrompt, prompt, estimatedTokens, note, incomplete, null);
        }
    }

    /** 검증 결과, 버린 것이 있었는지, 확인된 시간 조건. */
    record Parsed(List<RequestScope> scopes, boolean dropped, ScopeReport.StudyTime studyTime) {
        Parsed(List<RequestScope> scopes, boolean dropped) {
            this(scopes, dropped, null);
        }
    }

    /** 핸들 → 서버 값. 이번 호출에서 보여 준 것만 들어 있다. */
    record Registry(Map<String, CourseCatalog> courses, Map<String, CourseMaterial> materials,
                    Map<String, String> materialCourse,
                    /** 자료 목록을 상한까지만 보여 준 과목 → 생략한 개수 */
                    Map<String, Integer> omitted) {
    }

    public Result extract(Request request) {
        if (request.instruction() == null || request.instruction().isBlank() || request.catalogs().isEmpty()) {
            return new Result(ScopeReport.NONE, List.of(), null, null, null, "사용자 지시문이 없다");
        }
        Registry registry = registry(request.catalogs());
        String prompt = prompt(request, registry);
        int estimated = estimator.estimateCall(SYSTEM_PROMPT, prompt);
        String json;
        try {
            json = call(request, prompt);
        } catch (RuntimeException e) {
            log.warn("범위 추출 실패 — 범위 없이 계속한다: userId={}, {}", request.userId(), e.getClass().getSimpleName());
            return new Result(ScopeReport.FAILED, List.of(), SYSTEM_PROMPT, prompt, estimated, "범위 추출 호출 실패");
        }
        Parsed parsed = parse(json, registry, request.instruction(), request.timeReference());
        if (parsed == null) {
            return new Result(ScopeReport.FAILED, List.of(), SYSTEM_PROMPT, prompt, estimated, "범위 추출 응답을 읽지 못했다");
        }
        if (parsed.dropped()) {
            log.warn("범위 추출: 상한을 넘거나 읽을 수 없는 범위·부분을 버렸다. userId={}", request.userId());
        }
        boolean nothing = parsed.scopes().isEmpty() && !parsed.dropped() && parsed.studyTime() == null;
        return new Result(nothing ? ScopeReport.NONE : ScopeReport.EXTRACTED,
                parsed.scopes(), SYSTEM_PROMPT, prompt, estimated,
                parsed.dropped() ? "상한·형식 때문에 일부 범위를 버렸다" : null, parsed.dropped(), parsed.studyTime());
    }

    // ===== 입력 =====

    static Registry registry(List<CourseCatalog> catalogs) {
        Map<String, CourseCatalog> courses = new LinkedHashMap<>();
        Map<String, CourseMaterial> materials = new LinkedHashMap<>();
        Map<String, String> materialCourse = new LinkedHashMap<>();
        Map<String, Integer> omitted = new LinkedHashMap<>();
        int c = 1;
        int d = 1;
        for (CourseCatalog catalog : catalogs) {
            if (catalog.courseId() == null) {
                continue; // 프로젝트 없는 지정 자료는 과목 범위의 대상이 아니다
            }
            String ch = "c" + c++;
            courses.put(ch, catalog);
            List<CourseMaterial> ordered = ordered(catalog.materials());
            for (CourseMaterial m : ordered.subList(0, Math.min(ordered.size(), MAX_MATERIALS_PER_COURSE))) {
                String dh = "d" + d++;
                materials.put(dh, m);
                materialCourse.put(dh, ch);
            }
            if (ordered.size() > MAX_MATERIALS_PER_COURSE) {
                omitted.put(ch, ordered.size() - MAX_MATERIALS_PER_COURSE);
            }
        }
        return new Registry(courses, materials, materialCourse, omitted);
    }

    /**
     * 올린 순서. 파일명 앞 번호로 다시 정렬하지 않는다 — 번호 없는 자료(예 "파이썬기초_S")가 맨 뒤로 밀려 "…까지" 누적 범위에서
     * 빠졌다(4단계 실호출). 수업 순서는 대개 올린 순서와 같고, 주차·번호는 파일명에 보이므로 모델이 함께 판단한다.
     */
    static List<CourseMaterial> ordered(List<CourseMaterial> materials) {
        if (materials == null) {
            return List.of();
        }
        return materials.stream().filter(m -> m != null && m.getMaterialId() != null)
                .sorted(Comparator.comparing(CourseMaterial::getMaterialId))
                .toList();
    }

    static String prompt(Request request, Registry registry) {
        StringBuilder sb = new StringBuilder("[사용자 요청 원문]\n").append(request.instruction().strip()).append("\n\n");
        if (request.timeReference() != null) {
            sb.append("[시각 기준] ").append(request.timeReference()).append("\n\n");
        }
        if (request.agreedLines() != null && !request.agreedLines().isEmpty()) {
            sb.append("[상담 합의 — 참고만, 근거 구절은 위 원문에서]\n");
            request.agreedLines().forEach(l -> sb.append("- ").append(l).append('\n'));
            sb.append('\n');
        }
        sb.append("[과목과 자료 — 자료는 순서대로]\n");
        for (Map.Entry<String, CourseCatalog> course : registry.courses().entrySet()) {
            sb.append("== ").append(course.getKey()).append(' ').append(course.getValue().courseTitle()).append('\n');
            boolean any = false;
            for (Map.Entry<String, CourseMaterial> m : registry.materials().entrySet()) {
                if (!course.getKey().equals(registry.materialCourse().get(m.getKey()))) {
                    continue;
                }
                any = true;
                CourseMaterial material = m.getValue();
                sb.append("  ").append(m.getKey()).append(' ').append(material.getOriginalFilename());
                if (material.getPageCount() != null) {
                    sb.append(" (").append(material.getPageCount()).append("쪽)");
                }
                sb.append('\n');
            }
            if (!any) {
                sb.append("  (앱에 올린 자료 없음)\n");
            }
            Integer omitted = registry.omitted().get(course.getKey());
            if (omitted != null) {
                sb.append("  (자료 ").append(omitted).append("개는 목록에서 생략 — 여기 없다고 앱에 없는 것은 아니다)\n");
            }
        }
        return sb.toString();
    }

    // ===== 호출 =====

    private String call(Request request, String prompt) {
        AiStreamParser parser = new AiStreamParser();
        AtomicReference<Usage> lastUsage = new AtomicReference<>();
        AtomicReference<String> finishReason = new AtomicReference<>();
        try {
            aiConsultationClient.streamTurn(SYSTEM_PROMPT, prompt, maxCompletionTokens)
                    .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                    .doOnNext(chatResponse -> {
                        parser.onChunk(AiChatResponseUtils.extractText(chatResponse));
                        Usage usage = AiChatResponseUtils.extractUsage(chatResponse);
                        if (usage != null) {
                            lastUsage.set(usage);
                        }
                        String reason = AiChatResponseUtils.extractFinishReason(chatResponse);
                        if (reason != null) {
                            finishReason.set(reason);
                        }
                    })
                    // timeout은 청크 사이 무응답, blockLast의 제한은 구독부터 끝까지의 전체 시간이다.
                    .blockLast(Duration.ofSeconds(requestTimeoutSeconds));
        } catch (RuntimeException e) {
            record(request, lastUsage.get(), UsageResultStatus.FAILED, "SCOPE_FAILED");
            throw e;
        }
        if (AiChatResponseUtils.isTruncatedByTokenLimit(finishReason.get())) {
            record(request, lastUsage.get(), UsageResultStatus.FAILED, "TRUNCATED");
            throw new IllegalStateException("범위 추출 출력이 상한에서 잘렸다");
        }
        record(request, lastUsage.get(), UsageResultStatus.SUCCESS, null);
        AiStreamParser.Result result = parser.finish();
        // 구분자를 빼먹은 응답도 버리지 않는다 — 본문 전체에서 JSON 객체를 찾는다(파싱·검증은 같다).
        return result.structuredJson() != null ? result.structuredJson() : result.reply();
    }

    private void record(Request request, Usage usage, UsageResultStatus status, String errorCode) {
        aiUsageLimitService.record(request.userId(), null, null, modelName,
                AiChatResponseUtils.safeTokenCount(usage, true), null,
                AiChatResponseUtils.safeTokenCount(usage, false), status, errorCode,
                FEATURE, request.workflowId(), UUID.randomUUID().toString(), null);
    }

    // ===== 응답 검증 =====

    /** 모델 응답을 서버가 확정한 범위로 바꾼다. 읽을 수 없으면 null. */
    Parsed parse(String raw, Registry registry, String instruction) {
        return parse(raw, registry, instruction, null);
    }

    Parsed parse(String raw, Registry registry, String instruction, java.time.LocalTime timeReference) {
        String json = ModelJson.unwrapObject(raw);
        if (json == null) {
            return null;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
        if (root == null || !root.isObject() || !root.has("scopes") || !root.get("scopes").isArray()) {
            return null;
        }
        List<PlanBriefService.UserUtterance> said = List.of(new PlanBriefService.UserUtterance(0L, instruction));
        List<RequestScope> out = new ArrayList<>();
        boolean dropped = false;
        for (JsonNode s : root.get("scopes")) {
            if (!s.isObject()) {
                dropped = true;
                continue;
            }
            if (out.size() >= MAX_SCOPES) {
                dropped = true;
                break;
            }
            String courseHandle = handle(text(s, "course"));
            CourseCatalog course = courseHandle == null ? null : registry.courses().get(courseHandle);
            String quote = text(s, "quote");
            PlanBriefService.UserBasis basis = PlanBriefService.verifyQuote(quote, said);
            String scopeId = "S" + (out.size() + 1);
            List<RequestScope.Part> parts = new ArrayList<>();
            JsonNode partsNode = s.get("parts");
            if (partsNode != null && partsNode.isArray()) {
                for (JsonNode p : partsNode) {
                    if (!p.isObject()) {
                        dropped = true;
                        continue;
                    }
                    if (parts.size() >= MAX_PARTS) {
                        dropped = true;
                        break;
                    }
                    String label = cut(text(p, "label"), MAX_LABEL_CHARS);
                    if (label == null) {
                        dropped = true;
                        continue;
                    }
                    Set<Long> materialIds = new LinkedHashSet<>();
                    JsonNode ms = p.get("materials");
                    if (ms != null && ms.isArray()) {
                        for (JsonNode m : ms) {
                            String dh = handle(m.asText(null));
                            // 이번에 보여 준 자료이면서 같은 과목의 것만. 과목 미확정 범위는 자료를 연결하지 않는다.
                            if (dh != null && courseHandle != null && registry.materials().containsKey(dh)
                                    && courseHandle.equals(registry.materialCourse().get(dh))) {
                                materialIds.add(registry.materials().get(dh).getMaterialId());
                            }
                        }
                    }
                    // 목록을 다 보여 주지 않은 과목이면 "앱에 없음"을 믿지 않는다(생략한 자료일 수 있다 — 연결 미확인으로 남는다).
                    boolean listed = courseHandle != null && !registry.omitted().containsKey(courseHandle);
                    boolean noMaterial = p.path("noMaterial").asBoolean(false) && materialIds.isEmpty() && listed;
                    parts.add(new RequestScope.Part(scopeId + ".P" + (parts.size() + 1), label, List.copyOf(materialIds),
                            noMaterial, p.path("userMaterial").asBoolean(false)));
                }
            }
            String boundary = cut(text(s, "boundary"), MAX_GOAL_CHARS);
            if (parts.isEmpty()) {
                // 부분을 나누지 않았으면 범위 전체를 한 부분으로 둔다 — 대조 단위가 없어지지 않게.
                parts.add(new RequestScope.Part(scopeId + ".P1", boundary != null ? cut(boundary, MAX_LABEL_CHARS) : "범위 전체",
                        List.of(), false, false));
            }
            String role = RequestScope.OPTIONAL.equalsIgnoreCase(text(s, "role")) ? RequestScope.OPTIONAL : RequestScope.REQUIRED;
            out.add(new RequestScope(scopeId, course == null ? null : course.courseId(),
                    course == null ? null : course.courseTitle(), role, cut(text(s, "goal"), MAX_GOAL_CHARS), boundary,
                    cut(quote, 600), basis == null ? null : basis.sentence(), basis != null, List.copyOf(parts)));
        }
        return new Parsed(out, dropped, studyTime(root.get("studyTime"), instruction, timeReference));
    }

    /**
     * 쓸 수 있는 시간. 다음을 모두 만족할 때만 확인된 값이다 — 하나라도 어긋나면 null(모델이 정한 시간 값을 믿지 않는다).
     * <ul>
     *   <li>근거 구절이 사용자 발언 한 줄에 그대로 있다(같은 구절이 여러 줄에 있으면 가장 나중 줄).</li>
     *   <li>그 뒤의 발언에 시간 표현이 없다 — 나중에 바꿔 말한 시간을 두고 예전 구절을 고르지 못하게.</li>
     *   <li>분·단위·시작·끝이 그 발언 줄 전체에서 그대로 읽히고, 그 줄의 분량·시간대를 빠뜨리지 않았으며, 오전·오후를 말하지 않은
     *       시각은 기준 시각 규칙과 같다({@link PlanBriefService#timeValuesMatchFully}). 줄 전체로 대조해 "1.5시간"의 "5시간"
     *       같은 조각 인용이 통과하지 못한다.</li>
     * </ul>
     */
    static ScopeReport.StudyTime studyTime(JsonNode node, String instruction, java.time.LocalTime timeReference) {
        if (node == null || !node.isObject() || instruction == null) {
            return null;
        }
        List<String> lines = utteranceLines(instruction);
        String quoteText = text(node, "quote");
        int at = -1;
        PlanBriefService.UserBasis basis = null;
        for (int i = lines.size() - 1; i >= 0 && at < 0; i--) {
            PlanBriefService.UserBasis b = PlanBriefService.verifyQuote(quoteText,
                    List.of(new PlanBriefService.UserUtterance(0L, lines.get(i))));
            if (b != null) {
                at = i;
                basis = b;
            }
        }
        if (basis == null) {
            return null;
        }
        for (int i = at + 1; i < lines.size(); i++) {
            if (PlanBriefService.mentionsTime(lines.get(i))) {
                return null; // 나중 발언에 시간 표현이 있다 — 어느 것이 지금 조건인지 서버가 정할 수 없다
            }
        }
        String line = lines.get(at);
        Integer minutes = node.hasNonNull("minutes") && node.get("minutes").canConvertToInt() ? node.get("minutes").asInt() : null;
        if (minutes != null && (minutes < 5 || minutes > 9999)) {
            minutes = null;
        }
        String from = PlanBriefService.clockTime(text(node, "from"), false);
        String until = PlanBriefService.clockTime(text(node, "until"), true);
        if (from != null && until != null && !"24:00".equals(until) && from.compareTo(until) >= 0) {
            return null; // 앞뒤가 뒤집힌 시간대
        }
        if (minutes == null && from == null && until == null) {
            return null;
        }
        boolean perDay = minutes == null || !"PLAN".equalsIgnoreCase(text(node, "per"));
        PlanBriefService.TimeBudget budget = new PlanBriefService.TimeBudget(minutes, perDay,
                from == null ? null : java.time.LocalTime.parse(from), until);
        if (!PlanBriefService.timeValuesMatchFully(budget, line, timeReference)) {
            return null;
        }
        return new ScopeReport.StudyTime(minutes, perDay, from, until, basis.quote(), line);
    }

    /**
     * 지시문의 사용자 발언. 대화 지시문은 발언마다 "- "로 시작하고 발언 안의 줄바꿈은 다음 "- "까지 이어진다 — 한 발언의 여러 줄을
     * 따로 대조하면 그 발언의 분량·시간대 일부가 빠진 채 통과한다. "- " 줄이 없으면(화면에서 바로 쓴 지시문) 전체가 한 발언이다.
     * 첫 "- " 앞의 줄은 서버 머리말이다.
     */
    static List<String> utteranceLines(String instruction) {
        String[] raw = instruction.split("\\R");
        // 대화 지시문인지는 서버가 붙인 머리말로만 판단한다 — 화면에서 쓴 글의 불릿("- ")을 발언 경계로 보지 않는다.
        if (!instruction.strip().startsWith(CONVERSATION_HEADER.strip())) {
            return instruction.isBlank() ? List.of() : List.of(instruction.strip());
        }
        List<String> out = new ArrayList<>();
        StringBuilder current = null;
        for (String r : raw) {
            String line = r.strip();
            if (r.startsWith("- ")) { // 경계는 줄 맨 앞의 "- "뿐 — 들여 쓴 줄은 같은 메시지의 이어지는 줄이다
                if (current != null) {
                    out.add(current.toString().strip());
                }
                current = new StringBuilder(line.substring(2).strip());
            } else if (current != null && !line.isEmpty()) {
                current.append('\n').append(line);
            }
        }
        if (current != null && !current.toString().isBlank()) {
            out.add(current.toString().strip());
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText(null);
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String handle(String raw) {
        if (raw == null) {
            return null;
        }
        String h = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s#\\[\\]]", "");
        return h.matches("^[cd]\\d+$") ? h : null;
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return null;
        }
        String flat = s.replaceAll("\\s+", " ").strip();
        return flat.length() <= max ? flat : flat.substring(0, max);
    }
}
