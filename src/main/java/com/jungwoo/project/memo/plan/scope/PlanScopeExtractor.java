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

            응답 형식(반드시 지킨다):
            1) 뽑은 범위를 한 문장으로.
            2) 다음 줄에 정확히: %s
            3) 그 아래 JSON 객체 하나:
            {"scopes":[{"course":"c1"|null,"role":"REQUIRED"|"OPTIONAL","goal":"…","boundary":"…","quote":"사용자 원문 구절",
              "parts":[{"label":"…","materials":["d3"],"noMaterial":false,"userMaterial":false}]}]}
            사용자가 범위를 하나도 말하지 않았으면 {"scopes":[]}.
            """.formatted(AiStreamParser.DELIMITER);

    /**
     * @param instruction  이번 요청의 사용자 원문(서버가 사용자 메시지로 만든 지시문). 근거 구절은 여기서만 확인한다
     * @param agreedLines  유효한 상담 합의(참고용). 근거로 쓰지 않는다
     */
    public record Request(Long userId, String workflowId, String instruction, List<String> agreedLines,
                          List<CourseCatalog> catalogs) {
    }

    /** @param prompt 사용자 프롬프트(생성 기록용). 호출하지 않았으면 null */
    public record Result(String status, List<RequestScope> scopes, String systemPrompt, String prompt,
                         Integer estimatedTokens, String note,
                         /** 상한을 넘거나 읽을 수 없는 원소가 있어 버린 범위·부분이 있다 */
                         boolean incomplete) {
        public Result(String status, List<RequestScope> scopes, String systemPrompt, String prompt, Integer estimatedTokens,
                      String note) {
            this(status, scopes, systemPrompt, prompt, estimatedTokens, note, false);
        }
    }

    /** 검증 결과와 버린 것이 있었는지. */
    record Parsed(List<RequestScope> scopes, boolean dropped) {
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
        Parsed parsed = parse(json, registry, request.instruction());
        if (parsed == null) {
            return new Result(ScopeReport.FAILED, List.of(), SYSTEM_PROMPT, prompt, estimated, "범위 추출 응답을 읽지 못했다");
        }
        if (parsed.dropped()) {
            log.warn("범위 추출: 상한을 넘거나 읽을 수 없는 범위·부분을 버렸다. userId={}", request.userId());
        }
        return new Result(parsed.scopes().isEmpty() && !parsed.dropped() ? ScopeReport.NONE : ScopeReport.EXTRACTED,
                parsed.scopes(), SYSTEM_PROMPT, prompt, estimated,
                parsed.dropped() ? "상한·형식 때문에 일부 범위를 버렸다" : null, parsed.dropped());
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
        return new Parsed(out, dropped);
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
