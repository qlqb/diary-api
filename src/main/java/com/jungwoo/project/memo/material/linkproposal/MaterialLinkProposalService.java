package com.jungwoo.project.memo.material.linkproposal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.ai.AiChatResponseUtils;
import com.jungwoo.project.memo.ai.AiConsultationClient;
import com.jungwoo.project.memo.ai.AiStreamParser;
import com.jungwoo.project.memo.ai.AiUsageLimitService;
import com.jungwoo.project.memo.ai.domain.UsageResultStatus;
import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.course.CourseMapper;
import com.jungwoo.project.memo.course.CourseService;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.course.domain.CourseStatus;
import com.jungwoo.project.memo.course.dto.CourseCreateRequest;
import com.jungwoo.project.memo.course.dto.CourseResponse;
import com.jungwoo.project.memo.material.CourseMaterialMapper;
import com.jungwoo.project.memo.material.MaterialLinkMapper;
import com.jungwoo.project.memo.material.MaterialService;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.ExtractionStatus;
import com.jungwoo.project.memo.material.domain.MaterialLink;
import com.jungwoo.project.memo.material.domain.ProposalAction;
import com.jungwoo.project.memo.material.dto.LinkProposalApplyRequest;
import com.jungwoo.project.memo.material.dto.LinkProposalApplyResponse;
import com.jungwoo.project.memo.material.dto.LinkProposalPayload;
import com.jungwoo.project.memo.material.dto.LinkProposalResponse;
import com.jungwoo.project.memo.material.dto.ProjectRef;
import com.jungwoo.project.memo.material.dto.ProposalGroupResponse;
import com.jungwoo.project.memo.material.dto.ProposalMemberResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 미연결 자료 묶음을 받아 "어느 프로젝트에 넣을지"를 제안한다.
 *
 * 제안은 저장하지 않는다 — DRAFT 행을 만들지 않고, 사용자가 승인한 결과(apply)만 저장한다.
 * 그래서 제안 응답의 remainingMaterialIds가 커서 역할을 한다.
 *
 * Material Agent(MaterialAnalysisService)와는 하는 일이 다르다. 저쪽은 자료에서 과목 구조를
 * 뽑고, 이쪽은 자료에 이름표를 단다. 서로 건드리지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaterialLinkProposalService {

    private static final String FEATURE = "LINK_PROPOSAL";

    /**
     * 한 번에 다루는 후보 상한.
     *
     * 입력은 §발췌의 문자 수 분배로 조절되지만 출력은 후보 수에 비례해 늘어난다. 30개를
     * 넘기면 JSON이 중간에 잘려 파싱이 통째로 실패하고, 그러면 전부 잃는다.
     */
    static final int MAX_PROPOSAL_CANDIDATES = 12;

    private static final int EXCERPT_TOTAL_CHARS = 8000;
    private static final int EXCERPT_MAX_PER_FILE = 1500;

    /** PPTX 발췌는 앞 5장까지만. 자료의 정체는 첫머리에 있다. */
    private static final int PPTX_MAX_SLIDES = 5;

    /** TextExtractionService가 PPTX에 남기는 슬라이드 경계. 저쪽이 바뀌면 여기도 바뀐다. */
    private static final Pattern SLIDE_MARKER = Pattern.compile("\\[슬라이드 \\d+]");

    /** CourseCreateRequest의 @Size(max = 200)와 같은 값이어야 한다. */
    private static final int MAX_TITLE_LENGTH = 200;

    private final MaterialService materialService;
    private final CourseService courseService;
    private final CourseMaterialMapper courseMaterialMapper;
    private final MaterialLinkMapper materialLinkMapper;
    private final CourseMapper courseMapper;
    private final AiConsultationClient aiConsultationClient;
    private final AiUsageLimitService aiUsageLimitService;
    private final ProposalNormalizer proposalNormalizer;
    private final ObjectMapper objectMapper;

    @Value("${spring.ai.openai.chat.model:gpt-5-mini}")
    private String modelName = "gpt-5-mini";

    @Value("${ai.material.max-completion-tokens:4000}")
    private int maxCompletionTokens = 4000;

    @Value("${ai.request.timeout-seconds:90}")
    private int requestTimeoutSeconds = 90;

    private static final String SYSTEM_PROMPT_BODY = """
            당신은 사용자가 올려둔 자료를 어느 프로젝트에 넣을지 제안합니다.
            결정하지 않습니다. 사용자가 검토하고 승인합니다.

            ## 프로젝트란
            사용자가 일정 기간 이어서 다루는 주제 단위입니다. 학교 과목일 수도,
            자격증 준비나 개인 프로젝트, 반복하는 활동일 수도 있습니다.
            자료의 내용을 보고 판단하고, 학습 자료일 것이라고 미리 가정하지 마세요.

            ## 하는 일
            자료를 묶고, 각 묶음을 어떻게 처리할지 고릅니다.

            1. LINK_EXISTING — 이미 있는 프로젝트에 넣습니다.
               그 프로젝트와 같은 대상을 다룬다는 근거가 자료 안에 있을 때 고릅니다.
            2. CREATE_AND_LINK — 새 프로젝트를 만들어 넣습니다.
               기존 프로젝트 중 맞는 것이 없을 때 고릅니다.
            3. LEAVE — 지금은 그냥 둡니다.

            ## 묶는 기준
            같은 대상을 가리킨다는 근거가 자료 안에 있을 때만 묶습니다.
            같은 날 올라왔다거나 파일명이 비슷하다는 것만으로는 묶지 않습니다.
            이름이 같아 보인다는 것도 근거가 아닙니다.
            근거가 없으면 각각 따로 둡니다. 따로 두는 것이 안전한 선택입니다.

            ## LEAVE를 고르는 경우
            - 무엇에 관한 자료인지 본문에서 알 수 없을 때
            - 파일명 외에 단서가 없고, 그 파일명이 내용을 말해주지 않을 때
              (예: "문서1.pdf", "스캔_0412.pdf")
            - 프로젝트로 묶을 성격의 자료가 아닐 때
            - 어느 프로젝트에 넣을지 후보가 둘 이상이고 고를 근거가 없을 때
            판단이 서지 않으면 LEAVE입니다. 그럴듯한 이름을 지어내지 마세요.
            LEAVE는 정상적인 결과입니다.

            ## proposedTitle
            사용자가 이 주제를 부를 법한 이름을 씁니다.
            - 파일명을 그대로 쓰지 않습니다 ("OS_syllabus_2026.pdf" → "운영체제")
            - 확장자, 연도, 버전 표기, 담당자 이름을 넣지 않습니다
            - 자료의 종류가 아니라 주제입니다 ("강의계획서"가 아니라 "운영체제")

            ## evidence / evidenceSource
            evidence에는 실제로 본 것을 적습니다. 본문에서 봤으면 그 대목을 본문에 적힌
            표현 그대로 옮깁니다. 요약하거나 바꿔 쓰지 마세요.
            파일명에서 봤으면 파일명의 어느 부분인지를 적습니다.
            추론한 결과가 아니라 근거 자체입니다.

            evidenceSource는 정직하게 신고합니다.
            - CONTENT: 본문 발췌에서 확인했습니다
            - FILENAME_ONLY: 파일명만 보고 판단했습니다
            본문 발췌가 비어 있거나 판단에 쓰지 않았다면 FILENAME_ONLY입니다.

            ## materialType
            그 프로젝트에서 이 자료가 맡을 역할입니다. 파일의 종류가 아닙니다.
            SYLLABUS: 다룰 범위와 일정을 담은 자료
            TEXTBOOK_TOC: 내용의 구조나 목차
            PROFESSOR_SLIDE: 설명하는 본문 자료
            OTHER: 위에 해당하지 않음
            확신이 없으면 OTHER입니다.

            ## 지켜야 할 것
            - 입력으로 받은 materialId는 모두, 정확히 한 번씩 나와야 합니다.
              빠뜨리거나 중복하지 않습니다.
            - LEAVE 묶음은 하나로 모읍니다.
            - existingCourseId는 입력에 주어진 id 중에서만 고릅니다.
            - reason은 한국어 한 문장입니다. 사용자가 읽을 문장이므로
              "부족하다", "실패", "미흡" 같은 표현은 쓰지 않습니다.
              무엇을 보고 그렇게 판단했는지만 담담하게 씁니다.
            """;

    /**
     * 출력 규약. AiConsultationClient가 스트리밍만 제공하므로 AiStreamParser로 구분자 뒤
     * JSON을 꺼낸다. 이 기능에는 보여줄 reply가 없으므로 구분자 앞은 비운다.
     *
     * ★ 2단계(.entity() 단발 호출)로 이주하면 이 상수와 아래 연결을 통째로 지운다.
     *   남겨두면 모델이 JSON 앞에 구분자를 붙여 파싱이 그대로 깨진다.
     *   구분자 문자열은 AiStreamParser.DELIMITER와 같아야 한다.
     */
    private static final String OUTPUT_CONTRACT = """

            ## 출력 형식
            설명이나 인사말을 쓰지 마세요. 첫 줄에 아래 구분자만 쓰고, 그 다음 줄부터
            JSON만 씁니다. 코드 블록 표시(```)를 쓰지 마세요.

            <<<AI_STRUCTURED>>>
            {
              "groups": [
                {
                  "action": "LINK_EXISTING | CREATE_AND_LINK | LEAVE",
                  "existingCourseId": 12,
                  "proposedTitle": "운영체제",
                  "reason": "한 문장",
                  "members": [
                    {
                      "materialId": 101,
                      "materialType": "SYLLABUS | TEXTBOOK_TOC | PROFESSOR_SLIDE | OTHER",
                      "evidence": "근거로 삼은 대목",
                      "evidenceSource": "CONTENT | FILENAME_ONLY"
                    }
                  ]
                }
              ]
            }

            action이 LINK_EXISTING이 아니면 existingCourseId는 null입니다.
            action이 CREATE_AND_LINK가 아니면 proposedTitle은 null입니다.
            """;

    private static final String SYSTEM_PROMPT = SYSTEM_PROMPT_BODY + OUTPUT_CONTRACT;

    /**
     * 제안을 만든다. 저장하는 것이 없고 모델 호출은 트랜잭션 밖이 원칙이라 @Transactional을
     * 붙이지 않는다.
     *
     * 모델 호출 실패를 예외로 밖에 던지지 않는다 — 제안은 부가 기능이라 실패해도 업로드
     * 흐름을 막으면 안 된다. 다만 사용량 한도 초과만은 기존 API 정책대로 전파한다:
     * 모델 장애는 다시 눌러보면 되지만 한도 초과는 다시 눌러도 같은 결과라서, 같은 상태로
     * 묶으면 무의미한 재시도를 유도하게 된다.
     */
    public LinkProposalResponse propose(Long userId, List<Long> materialIds, ProposalTrigger trigger) {
        ProposalTrigger source = ProposalTrigger.orDefault(trigger);

        List<CourseMaterial> pool = collectPool(userId, materialIds);
        if (pool.isEmpty()) {
            // 모델을 부르지 않을 요청으로 한도를 소모시키지 않는다 — checkLimit도 여기선 건너뛴다.
            logEnded(source, ProposalStatus.NO_CANDIDATES, 0);
            return LinkProposalResponse.noCandidates();
        }

        List<CourseMaterial> selected = pool.size() > MAX_PROPOSAL_CANDIDATES
                ? pool.subList(0, MAX_PROPOSAL_CANDIDATES) : pool;
        List<Long> remainingMaterialIds = pool.size() > MAX_PROPOSAL_CANDIDATES
                ? pool.subList(MAX_PROPOSAL_CANDIDATES, pool.size()).stream()
                        .map(CourseMaterial::getMaterialId).toList()
                : List.of();

        List<ProposalCandidate> candidates = toCandidates(selected);
        List<ProjectRef> activeProjects = activeProjects(userId);

        aiUsageLimitService.checkLimit(userId);

        Optional<LinkProposalPayload> payload =
                callModel(userId, SYSTEM_PROMPT, buildUserPrompt(candidates, activeProjects));
        if (payload.isEmpty()) {
            logEnded(source, ProposalStatus.UNAVAILABLE, candidates.size());
            return LinkProposalResponse.unavailable();
        }

        List<ProposalGroupResponse> groups =
                proposalNormalizer.normalize(candidates, payload.get(), activeProjects);
        logOutcome(source, candidates, groups);
        return LinkProposalResponse.generated(groups, remainingMaterialIds);
    }

    /**
     * 후보 수집.
     *
     * "미연결"의 정의는 §2와 같다 — ACTIVE 프로젝트로의 링크가 하나도 없는 자료. 보관된
     * 프로젝트로의 연결만 있는 자료는 미연결로 본다. 화면의 `연결 안 된 자료` 필터가 이
     * 기준으로 동작하므로 제안도 같은 기준을 써야 한다.
     *
     * ★ 그 정의를 여기서 다시 구현하지 않고 MaterialLinkMapper.findByUserId에 기댄다 —
     *   그 SQL이 courses.status = 'ACTIVE'로 JOIN하고 있다. 조회 SQL이 바뀌면 여기가 같이
     *   깨진다.
     */
    private List<CourseMaterial> collectPool(Long userId, List<Long> materialIds) {
        List<CourseMaterial> materials;
        if (materialIds == null || materialIds.isEmpty()) {
            materials = courseMaterialMapper.findAllByUserId(userId);
        } else {
            materials = materialIds.stream()
                    .filter(Objects::nonNull)
                    .distinct()
                    .map(materialId -> materialService.getActiveOwned(userId, materialId))
                    .sorted(Comparator.comparing(CourseMaterial::getCreatedAt,
                            Comparator.nullsFirst(Comparator.naturalOrder())).reversed())
                    .toList();
        }

        Set<Long> linkedToActive = materialLinkMapper.findByUserId(userId).stream()
                .map(MaterialLink::getMaterialId)
                .collect(Collectors.toSet());

        return materials.stream()
                // 추출 실패 자료는 판단 근거가 파일명뿐이라 "새 프로젝트 스캔_0412를 만들까요?"가
                // 나온다. 한 번 그러면 사용자가 다음부터 카드를 읽지 않는다. 하드 게이트다.
                .filter(m -> m.getExtractionStatus() == ExtractionStatus.SUCCESS)
                .filter(m -> !linkedToActive.contains(m.getMaterialId()))
                .toList();
    }

    private List<ProjectRef> activeProjects(Long userId) {
        return courseMapper.findByUserIdAndStatus(userId, CourseStatus.ACTIVE.name()).stream()
                .map(course -> new ProjectRef(course.getCourseId(), course.getTitle()))
                .toList();
    }

    private List<ProposalCandidate> toCandidates(List<CourseMaterial> materials) {
        int perFile = Math.min(EXCERPT_MAX_PER_FILE, EXCERPT_TOTAL_CHARS / Math.max(1, materials.size()));
        return materials.stream()
                .map(m -> new ProposalCandidate(m.getMaterialId(), m.getOriginalFilename(),
                        buildExcerpt(m.getExtractedText(), perFile)))
                .toList();
    }

    /**
     * 발췌. 문자 수 기준이고 앞에서 자른다 — 자료의 정체는 첫머리에 있다.
     *
     * 발췌가 공백뿐이어도 후보에서 빼지 않는다. 모델이 LEAVE로 판단하게 두는 것이 의도다.
     */
    private String buildExcerpt(String text, int perFile) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String head = firstSlides(text);
        return head.length() > perFile ? head.substring(0, perFile) : head;
    }

    /** PPTX면 슬라이드 경계 기준 앞 5장까지만 남긴다. 경계가 없으면(PDF 등) 원문 그대로. */
    private String firstSlides(String text) {
        Matcher matcher = SLIDE_MARKER.matcher(text);
        int seen = 0;
        while (matcher.find()) {
            seen += 1;
            if (seen > PPTX_MAX_SLIDES) {
                return text.substring(0, matcher.start());
            }
        }
        return text;
    }

    private String buildUserPrompt(List<ProposalCandidate> candidates, List<ProjectRef> activeProjects) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 이미 있는 프로젝트\n");
        if (activeProjects.isEmpty()) {
            sb.append("아직 만들어 둔 프로젝트가 없습니다.\n");
        } else {
            for (ProjectRef project : activeProjects) {
                sb.append("id=").append(project.courseId()).append(" | ").append(project.title()).append('\n');
            }
        }

        sb.append("\n## 정리할 자료\n");
        for (ProposalCandidate candidate : candidates) {
            sb.append("\n[materialId=").append(candidate.materialId()).append("]\n");
            sb.append("파일명: ").append(candidate.originalFilename()).append('\n');
            sb.append("본문 발췌:\n");
            sb.append(candidate.hasExcerpt() ? candidate.excerpt() : "(없음)").append('\n');
        }
        return sb.toString();
    }

    /**
     * 모델 호출. 반환형이 Optional인 것이 계약이다 — LinkProposalPayload에는 상태가 없으므로,
     * payload를 그대로 돌려주면 실패를 null / 빈 payload / 예외 중 무엇으로 표현할지가
     * 구현자에게 맡겨진다. Optional.empty()가 곧 UNAVAILABLE이다.
     *
     * ★ 2단계에서 시그니처는 그대로 두고 본문만 entityTurn 한 줄로 바꾼다.
     */
    private Optional<LinkProposalPayload> callModel(Long userId, String systemPrompt, String userPrompt) {
        if (!aiConsultationClient.isConfigured()) {
            return Optional.empty();
        }

        // Usage 참조는 try 밖에 둔다 — 스트리밍 도중 예외가 나도 그때까지 모은 값을 catch에서
        // 기록해야 한다. MaterialAnalysisService와 같은 형태.
        AtomicReference<Usage> lastUsage = new AtomicReference<>();
        AiStreamParser parser = new AiStreamParser();

        try {
            aiConsultationClient.streamTurn(systemPrompt, userPrompt, maxCompletionTokens)
                    .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                    .doOnNext(chatResponse -> {
                        parser.onChunk(AiChatResponseUtils.extractText(chatResponse));
                        Usage usage = AiChatResponseUtils.extractUsage(chatResponse);
                        if (usage != null) {
                            lastUsage.set(usage);
                        }
                    })
                    .blockLast();

            String json = parser.finish().structuredJson();
            if (json == null || json.isBlank()) {
                log.warn("연결 제안: 구분자 뒤 JSON이 없음. userId={}", userId);
                recordUsage(userId, lastUsage.get(), UsageResultStatus.FAILED,
                        ErrorCode.AI_GENERATION_FAILED.getCode());
                return Optional.empty();
            }

            LinkProposalPayload payload = objectMapper.readValue(json, LinkProposalPayload.class);
            recordUsage(userId, lastUsage.get(), UsageResultStatus.SUCCESS, null);
            return Optional.of(payload);

        } catch (Exception e) {
            // errorCode 자리에 e.getMessage()를 넣지 않는다 — 그 컬럼(ai_usage_logs.error_code)은
            // VARCHAR(20) 코드값이다. 예외 원문은 로그로만 남긴다.
            log.warn("연결 제안 모델 호출 실패: userId={}", userId, e);
            recordUsage(userId, lastUsage.get(), UsageResultStatus.FAILED,
                    ErrorCode.AI_GENERATION_FAILED.getCode());
            return Optional.empty();
        }
    }

    private void recordUsage(Long userId, Usage usage, UsageResultStatus resultStatus, String errorCode) {
        aiUsageLimitService.record(userId, null, null, modelName,
                AiChatResponseUtils.safeTokenCount(usage, true), null,
                AiChatResponseUtils.safeTokenCount(usage, false), resultStatus, errorCode,
                FEATURE, null, UUID.randomUUID().toString(), null);
    }

    /**
     * ai_usage_logs에는 "제안이 실제로 표시됐는가"를 담을 컬럼이 없다. 로그 두 줄로 남긴다.
     *
     * 미검증 비율과 selectable 개수는 한 세트로 본다 — 미검증이 잦으면 자동 카드가 거의 뜨지
     * 않게 되므로, 둘을 함께 봐야 게이트를 조정할지 대조 규칙을 손볼지 판단할 수 있다.
     * 실제 오탐 사례를 확인하기 전에는 대조 규칙을 완화하지 않는다.
     */
    private void logOutcome(ProposalTrigger trigger, List<ProposalCandidate> candidates,
                            List<ProposalGroupResponse> groups) {
        log.info("연결 제안: trigger={}, candidates={}, groups={}, selectable={}",
                trigger, candidates.size(), groups.size(),
                groups.stream().filter(ProposalGroupResponse::isDefaultSelected).count());

        List<ProposalMemberResponse> placed = groups.stream()
                .filter(g -> g.getAction() != ProposalAction.LEAVE)
                .flatMap(g -> g.getMembers().stream())
                .toList();
        long unverified = placed.stream().filter(m -> !m.isEvidenceVerified()).count();
        log.info("evidence 미검증: {}/{}", unverified, placed.size());
    }

    /**
     * 카드까지 가지 못하고 끝난 호출도 같은 줄을 남긴다. 자동 호출이 UNAVAILABLE로 끝난
     * 것을 세지 못하면 "자동 제안이 실제로 카드로 이어졌는지"의 분모가 비게 된다.
     */
    private void logEnded(ProposalTrigger trigger, ProposalStatus status, int candidateCount) {
        log.info("연결 제안: trigger={}, status={}, candidates={}", trigger, status, candidateCount);
    }

    /**
     * 승인된 제안을 적용한다. 단일 트랜잭션이고, 하나라도 실패하면 전부 롤백한다.
     *
     * 업로드와 반대다. 업로드는 파일마다 독립된 자료라 부분 성공이 정상이지만, 여기서
     * 사용자가 승인한 것은 화면에 보인 묶음 전체라는 한 덩어리다. 3개만 적용된 상태는
     * 사용자가 승인한 적 없는 상태다.
     *
     * 검증을 전부 마친 뒤에야 쓰기를 시작한다 — 5번과 6번을 섞지 않는다.
     */
    @Transactional
    public LinkProposalApplyResponse apply(Long userId, LinkProposalApplyRequest request) {
        List<LinkProposalApplyRequest.ApplyGroup> groups = request.getGroups();

        Set<Long> materialIds = new LinkedHashSet<>();
        for (LinkProposalApplyRequest.ApplyGroup group : groups) {
            if (group.getAction() == ProposalAction.LEAVE) {
                throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE,
                        "그냥 두기로 한 묶음은 적용할 수 없습니다");
            }
            for (LinkProposalApplyRequest.ApplyMember member : group.getMembers()) {
                if (!materialIds.add(member.getMaterialId())) {
                    throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE,
                            "같은 자료가 두 묶음에 들어 있습니다");
                }
            }
        }

        lockAndVerifyMaterials(userId, materialIds);
        Map<Long, Course> existingCourses = verifyGroups(userId, groups);

        List<ProjectRef> createdProjects = new ArrayList<>();
        int linkedCount = 0;
        for (LinkProposalApplyRequest.ApplyGroup group : groups) {
            Long courseId;
            if (group.getAction() == ProposalAction.CREATE_AND_LINK) {
                CourseResponse created = courseService.create(userId,
                        new CourseCreateRequest(group.getTitle().trim(), null));
                courseId = created.getCourseId();
                createdProjects.add(new ProjectRef(created.getCourseId(), created.getTitle()));
            } else {
                courseId = existingCourses.get(group.getExistingCourseId()).getCourseId();
            }

            for (LinkProposalApplyRequest.ApplyMember member : group.getMembers()) {
                // 기존 addLink 경로를 그대로 쓴다 — INSERT가 한 군데로 모이고, 이 경로가 이미
                // 갖고 있는 중복 링크 방어를 여기서 다시 만들지 않는다.
                materialService.addLink(userId, member.getMaterialId(), courseId, member.getMaterialType());
                linkedCount += 1;
            }
        }

        log.info("연결 제안 적용: userId={}, groups={}, createdProjects={}, linkedMaterials={}",
                userId, groups.size(), createdProjects.size(), linkedCount);

        return LinkProposalApplyResponse.builder()
                .createdProjects(createdProjects)
                .linkedMaterialCount(linkedCount)
                .build();
    }

    /**
     * 요청에 등장하는 자료를 한 번에, materialId 오름차순으로 잠근 뒤 다시 확인한다.
     *
     * 잠근 "뒤에" 다시 확인하는 것이 핵심이다. 그래야 두 요청이 같은 자료로 각각 새 프로젝트를
     * 만드는 일이 막힌다 — 뒤늦게 들어온 쪽은 앞선 요청이 커밋한 링크를 보고 거부된다.
     * (잠금 획득 이후의 첫 일관된 읽기라서 InnoDB REPEATABLE READ에서도 최신 커밋이 보인다.)
     */
    private void lockAndVerifyMaterials(Long userId, Set<Long> materialIds) {
        List<Long> ordered = materialIds.stream().sorted().toList();
        List<CourseMaterial> locked = courseMaterialMapper.lockActiveByIdsAndUserId(ordered, userId);
        if (locked.size() != ordered.size()) {
            throw new NotFoundException(ErrorCode.COURSE_MATERIAL_NOT_FOUND);
        }

        Set<Long> linkedToActive = materialLinkMapper.findByUserId(userId).stream()
                .map(MaterialLink::getMaterialId)
                .collect(Collectors.toSet());
        for (Long materialId : ordered) {
            if (linkedToActive.contains(materialId)) {
                throw new ConflictException(ErrorCode.MATERIAL_ALREADY_LINKED_TO_COURSE);
            }
        }
    }

    /** 목적지 검증. 쓰기를 시작하기 전에 전부 통과해야 한다. */
    private Map<Long, Course> verifyGroups(Long userId, List<LinkProposalApplyRequest.ApplyGroup> groups) {
        Map<Long, Course> existingCourses = new LinkedHashMap<>();
        for (LinkProposalApplyRequest.ApplyGroup group : groups) {
            if (group.getAction() == ProposalAction.LINK_EXISTING) {
                if (group.getExistingCourseId() == null) {
                    throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE, "연결할 프로젝트를 지정해주세요");
                }
                Course course = courseService.getOwned(userId, group.getExistingCourseId());
                if (course.getStatus() != CourseStatus.ACTIVE) {
                    throw new ConflictException(ErrorCode.COURSE_ARCHIVED);
                }
                existingCourses.put(course.getCourseId(), course);
            } else {
                String title = group.getTitle();
                if (title == null || title.isBlank()) {
                    throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE, "프로젝트 이름을 입력해주세요");
                }
                if (title.trim().length() > MAX_TITLE_LENGTH) {
                    throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE,
                            "프로젝트 이름은 " + MAX_TITLE_LENGTH + "자까지 쓸 수 있어요");
                }
            }
        }
        return existingCourses;
    }
}
