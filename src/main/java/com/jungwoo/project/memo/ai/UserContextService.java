package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.domain.AiProposal;
import com.jungwoo.project.memo.ai.domain.ContextEvidenceType;
import com.jungwoo.project.memo.ai.domain.ContextSourceType;
import com.jungwoo.project.memo.ai.domain.UserContext;
import com.jungwoo.project.memo.ai.domain.UserContextStatus;
import com.jungwoo.project.memo.ai.dto.UserContextResponse;
import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.course.CourseMapper;
import com.jungwoo.project.memo.course.domain.Course;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * "AI가 이해한 내 상황" — 사용자 상황의 조회·수정·확인·철회와, 상담 턴의 자동 저장.
 *
 * <p>(2026-09-19) 예전에는 조회만 있었고 변경은 AI 후보를 하나씩 승인하는 길뿐이었다. 지금은:
 * <ul>
 *   <li>사용자가 직접 말한 것·자기평가는 상담 턴이 출처와 범위를 붙여 <b>바로 저장</b>한다(승인 팝업 없음). 대신 언제든
 *       고치고 지울 수 있다.</li>
 *   <li>AI의 추정은 {@link ContextEvidenceType#INFERRED}로 남는다 — 사용자가 "맞아요"로 확인하기 전에는 사실이 아니다.</li>
 *   <li>지운 것은 WITHDRAWN이다. 같은 내용은 다시 저장하지 않는다(대화 기록에서 다시 뽑히는 것을 막는다).</li>
 *   <li>고치면 옛 행은 SUPERSEDED로 남고 새 행이 생긴다. 사용자가 고친 것은 STATED다.</li>
 * </ul>
 *
 * <p>이 서비스는 실행 항목·일정을 바꾸지 않는다. 기억이 바뀌면 그 기억을 근거로 만든 <b>열린 초안</b>이 오래됐다는 사실만
 * 알려 준다(staleDraftIds). 이미 적용한 일정은 승인 없이 바뀌지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserContextService {

    static final int MAX_CONTENT_CHARS = 300;
    static final int WITHDRAWN_LOOKBACK = 40;

    private final UserContextMapper userContextMapper;
    private final CourseMapper courseMapper;
    private final AiProposalMapper aiProposalMapper;

    /** 변경 결과. staleDraftIds는 이 기억을 근거로 든 열린 계획 초안이다. */
    public record Change(UserContextResponse context, List<Long> staleDraftIds) {
    }

    /**
     * 상담 턴이 저장하려는 한 줄.
     *
     * @param quote 사용자가 실제로 한 말의 일부. STATED·SELF_REPORT는 이 말이 이번 사용자 발화 안에 있을 때만 인정한다
     */
    public record AutoSave(String text, ContextEvidenceType evidenceType, Long courseId, LocalDate scopeStart,
                           LocalDate scopeEnd, String quote) {
    }

    /** ACTIVE/STALE만 반환한다(SUPERSEDED/ARCHIVED/WITHDRAWN은 이력일 뿐 현재 화면에 노출하지 않는다). */
    @Transactional(readOnly = true)
    public List<UserContextResponse> listActiveAndStale(Long userId) {
        Map<Long, String> titles = courseTitles(userId);
        return userContextMapper.findActiveAndStaleByUserId(userId, null).stream()
                .map(c -> toResponse(c, titles))
                .toList();
    }

    /** 고치기. 옛 행은 SUPERSEDED로 남기고 새 행을 만든다. 사용자가 고친 것은 사용자의 말이다(STATED). */
    @Transactional
    public Change edit(Long userId, Long contextId, String content) {
        String text = normalize(content);
        if (text == null) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }
        UserContext current = owned(userId, contextId);
        int rows = userContextMapper.updateStatusIfIn(contextId, userId, List.of("ACTIVE", "STALE"),
                UserContextStatus.SUPERSEDED);
        if (rows == 0) {
            throw new ConflictException(ErrorCode.CONTEXT_ALREADY_CHANGED);
        }
        UserContext next = UserContext.builder()
                .userId(userId).content(text).status(UserContextStatus.ACTIVE)
                .sourceType(ContextSourceType.USER_EDITED).evidenceType(ContextEvidenceType.STATED)
                .courseId(current.getCourseId()).topicId(current.getTopicId()).sectionId(current.getSectionId())
                .scopeStart(current.getScopeStart()).scopeEnd(current.getScopeEnd())
                .sourceMessageId(current.getSourceMessageId()).supersedesContextId(contextId)
                .confirmedAt(LocalDateTime.now()).build();
        userContextMapper.insert(next);
        UserContext saved = userContextMapper.findByIdAndUserId(next.getContextId(), userId);
        // 옛 id를 근거로 든 초안이 오래된 것이다.
        return new Change(toResponse(saved, courseTitles(userId)), draftsCiting(userId, contextId));
    }

    /** AI 추정을 "맞아요"로 확인한다. */
    @Transactional
    public Change confirm(Long userId, Long contextId) {
        owned(userId, contextId);
        if (userContextMapper.confirmInferred(contextId, userId, LocalDateTime.now()) == 0) {
            throw new ConflictException(ErrorCode.CONTEXT_ALREADY_CHANGED);
        }
        return new Change(toResponse(userContextMapper.findByIdAndUserId(contextId, userId), courseTitles(userId)),
                draftsCiting(userId, contextId));
    }

    /** 지우기(철회). 행은 남는다 — 같은 내용이 다시 저장되는 것을 막는 근거다. */
    @Transactional
    public Change withdraw(Long userId, Long contextId) {
        UserContext current = owned(userId, contextId);
        if (userContextMapper.withdraw(contextId, userId, LocalDateTime.now()) == 0) {
            throw new ConflictException(ErrorCode.CONTEXT_ALREADY_CHANGED);
        }
        current.setStatus(UserContextStatus.WITHDRAWN);
        return new Change(toResponse(current, courseTitles(userId)), draftsCiting(userId, contextId));
    }

    /**
     * 상담 턴의 자동 저장. 실패해도 턴은 실패하지 않는다(호출자가 잡는다).
     *
     * <p>서버가 지키는 것: (1) STATED·SELF_REPORT는 인용(quote)이 이번 사용자 발화 안에 있어야 한다 — 없으면 AI의 추정으로
     * 낮춘다. AI의 말이 사용자 동의 없이 사용자 상태가 되지 않는다. (2) 사용자가 지운 내용과 같은 것은 저장하지 않는다.
     * (3) 이미 같은 범위에 같은 내용이 있으면 새로 만들지 않는다. (4) 프로젝트는 사용자의 것만.
     *
     * @return 이번 턴에 새로 저장한 것
     */
    @Transactional
    public List<UserContextResponse> autoSave(Long userId, Long sourceMessageId, String userMessage, List<AutoSave> ops,
                                              int maxPerTurn) {
        if (ops == null || ops.isEmpty()) {
            return List.of();
        }
        Map<Long, String> titles = courseTitles(userId);
        List<UserContext> existing = userContextMapper.findActiveAndStaleByUserId(userId, null);
        List<UserContext> withdrawn = userContextMapper.findWithdrawnByUserId(userId, WITHDRAWN_LOOKBACK);
        String said = key(userMessage);
        List<UserContextResponse> saved = new ArrayList<>();
        for (AutoSave op : ops) {
            if (saved.size() >= maxPerTurn) {
                break;
            }
            String text = normalize(op.text());
            if (text == null) {
                continue;
            }
            Long courseId = op.courseId() != null && titles.containsKey(op.courseId()) ? op.courseId() : null;
            String k = key(text);
            if (withdrawn.stream().anyMatch(w -> key(w.getContent()).equals(k))) {
                log.info("상담 자동 저장: 사용자가 지운 내용과 같아 저장하지 않는다. userId={}", userId);
                continue;
            }
            if (existing.stream().anyMatch(e -> key(e.getContent()).equals(k) && Objects.equals(e.getCourseId(), courseId))) {
                continue;
            }
            ContextEvidenceType type = op.evidenceType() == null ? ContextEvidenceType.INFERRED : op.evidenceType();
            if (type == ContextEvidenceType.OBSERVED) {
                type = ContextEvidenceType.INFERRED; // 관찰은 서버가 실행 기록에서만 만든다. 모델이 주장할 수 없다.
            }
            if (type != ContextEvidenceType.INFERRED) {
                String quote = key(op.quote());
                if (quote.length() < 2 || !said.contains(quote)) {
                    type = ContextEvidenceType.INFERRED;
                }
            }
            LocalDate start = op.scopeStart();
            LocalDate end = op.scopeEnd();
            if (start != null && end != null && end.isBefore(start)) {
                start = null;
                end = null;
            }
            UserContext row = UserContext.builder()
                    .userId(userId).content(text).status(UserContextStatus.ACTIVE)
                    .sourceType(ContextSourceType.CONSULT_AUTO).evidenceType(type).courseId(courseId)
                    .scopeStart(start).scopeEnd(end).sourceMessageId(sourceMessageId)
                    .confirmedAt(type == ContextEvidenceType.INFERRED ? null : LocalDateTime.now()).build();
            userContextMapper.insert(row);
            existing.add(row);
            saved.add(toResponse(userContextMapper.findByIdAndUserId(row.getContextId(), userId), titles));
        }
        return saved;
    }

    /** 점검 활동의 자기평가 하나. 같은 대상의 예전 자기평가는 대체한다(이력은 남는다). */
    public record SelfCheck(String label, Long topicId, Long sectionId, String level, String note) {
    }

    @Transactional
    public int saveSelfChecks(Long userId, Long courseId, List<SelfCheck> items) {
        if (!courseTitles(userId).containsKey(courseId)) {
            throw new NotFoundException(ErrorCode.COURSE_NOT_FOUND);
        }
        List<UserContext> previous = userContextMapper.findActiveSelfChecks(userId, courseId);
        int saved = 0;
        for (SelfCheck item : items == null ? List.<SelfCheck>of() : items) {
            String label = normalize(item.label());
            String level = item.level() == null ? null : item.level().trim().toUpperCase(Locale.ROOT);
            if (label == null || level == null || !List.of("KNOW", "UNSURE", "NEW").contains(level)) {
                continue;
            }
            for (UserContext old : previous) {
                boolean sameTarget = item.topicId() != null ? Objects.equals(old.getTopicId(), item.topicId())
                        : item.sectionId() != null ? Objects.equals(old.getSectionId(), item.sectionId())
                        : key(old.getContent()).startsWith(key(label));
                if (sameTarget) {
                    userContextMapper.updateStatusIfIn(old.getContextId(), userId, List.of("ACTIVE", "STALE"),
                            UserContextStatus.SUPERSEDED);
                }
            }
            String words = switch (level) {
                case "KNOW" -> "안다고 답함";
                case "UNSURE" -> "애매하다고 답함";
                default -> "처음 본다고 답함";
            };
            String note = normalize(item.note());
            String content = label + " — " + words + (note == null ? "" : " (" + note + ")");
            userContextMapper.insert(UserContext.builder()
                    .userId(userId).content(cut(content)).status(UserContextStatus.ACTIVE)
                    .sourceType(ContextSourceType.SELF_CHECK).evidenceType(ContextEvidenceType.SELF_REPORT)
                    .courseId(courseId).topicId(item.topicId()).sectionId(item.sectionId()).selfLevel(level)
                    .confirmedAt(LocalDateTime.now()).build());
            saved++;
        }
        return saved;
    }

    /** 최근에 사용자가 지운 내용. 상담 프롬프트가 "다시 저장하지 않는다"로 싣는다. */
    @Transactional(readOnly = true)
    public List<String> recentlyWithdrawn(Long userId, int limit) {
        return userContextMapper.findWithdrawnByUserId(userId, limit).stream().map(UserContext::getContent).toList();
    }

    // ===== 내부 =====

    private UserContext owned(Long userId, Long contextId) {
        UserContext context = userContextMapper.findByIdAndUserId(contextId, userId);
        if (context == null) {
            throw new NotFoundException(ErrorCode.CONTEXT_NOT_FOUND);
        }
        return context;
    }

    /** 이 기억을 근거(USER_CONTEXT 출처)로 든 열린 계획 초안. 적용된 계획은 대상이 아니다. */
    private List<Long> draftsCiting(Long userId, Long contextId) {
        List<Long> out = new ArrayList<>();
        try {
            for (AiProposal proposal : aiProposalMapper.findOpenPlanDraftsByUserId(userId)) {
                String json = proposal.getPlanProvenanceJson();
                if (json != null && json.matches("(?s).*\"sourceType\"\\s*:\\s*\"USER_CONTEXT\"\\s*,\\s*\"sourceId\"\\s*:\\s*"
                        + contextId + "\\b.*")) {
                    out.add(proposal.getProposalId());
                }
            }
        } catch (Exception e) {
            log.warn("기억 변경의 영향 초안을 찾지 못했다: contextId={}, {}", contextId, e.getClass().getSimpleName());
        }
        return out;
    }

    private Map<Long, String> courseTitles(Long userId) {
        Map<Long, String> titles = new HashMap<>();
        List<Course> all = new ArrayList<>(courseMapper.findByUserIdAndStatus(userId, "ACTIVE"));
        all.addAll(courseMapper.findByUserIdAndStatus(userId, "ARCHIVED"));
        for (Course course : all) {
            titles.put(course.getCourseId(), course.getTitle());
        }
        return titles;
    }

    UserContextResponse toResponse(UserContext context, Map<Long, String> titles) {
        return UserContextResponse.builder()
                .contextId(context.getContextId())
                .content(context.getContent())
                .status(context.getStatus())
                .sourceType(context.getSourceType())
                .evidenceType(context.getEvidenceType() == null ? ContextEvidenceType.STATED : context.getEvidenceType())
                .courseId(context.getCourseId())
                .courseTitle(context.getCourseId() == null ? null : titles.get(context.getCourseId()))
                .topicId(context.getTopicId())
                .scopeStart(context.getScopeStart())
                .scopeEnd(context.getScopeEnd())
                .selfLevel(context.getSelfLevel())
                .sourceMessageId(context.getSourceMessageId())
                .supersedesContextId(context.getSupersedesContextId())
                .confirmedAt(context.getConfirmedAt())
                .createdAt(context.getCreatedAt())
                .updatedAt(context.getUpdatedAt())
                .build();
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String flat = raw.strip().replaceAll("\\s+", " ");
        return flat.isEmpty() ? null : cut(flat);
    }

    private static String cut(String s) {
        return s.length() > MAX_CONTENT_CHARS ? s.substring(0, MAX_CONTENT_CHARS) : s;
    }

    /** 비교용: 공백·문장부호를 빼고 소문자로. */
    static String key(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}·…“”‘’]+", "");
    }
}
