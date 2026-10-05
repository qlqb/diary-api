package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.domain.AiProposal;
import com.jungwoo.project.memo.ai.domain.ContextEvidenceType;
import com.jungwoo.project.memo.ai.domain.ContextSourceType;
import com.jungwoo.project.memo.ai.domain.FactKind;
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
                           LocalDate scopeEnd, String quote, FactKind kind, Long topicId, String label, String help,
                           Long resolves) {

        public AutoSave(String text, ContextEvidenceType evidenceType, Long courseId, LocalDate scopeStart,
                        LocalDate scopeEnd, String quote) {
            this(text, evidenceType, courseId, scopeStart, scopeEnd, quote, null, null, null, null, null);
        }
    }

    /**
     * 사용자 수정. content 말고는 선택이다 — null이면 옛 값을 그대로 둔다.
     *
     * @param topicId 단원을 바꾼다. 0이면 단원 연결을 끊는다
     */
    public record Edit(String content, FactKind kind, Long topicId, String help, String label) {
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
        return edit(userId, contextId, new Edit(content, null, null, null, null));
    }

    /**
     * 고치기(종류·단원·도움 수준까지). 고친 것은 지금 한 사용자의 말이라 같은 과목의 같은 현재 상태(진도·시험 범위)를 대체한다.
     */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Change edit(Long userId, Long contextId, Edit edit) {
        String text = normalize(edit == null ? null : edit.content());
        if (text == null) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }
        UserContext current = owned(userId, contextId);
        if (current.getCourseId() != null) {
            lockCourse(userId, current.getCourseId());
        }
        FactKind kind = edit.kind() != null ? edit.kind() : current.getFactKind();
        Long topicId = current.getTopicId();
        if (edit.topicId() != null) {
            topicId = edit.topicId() == 0L ? null : edit.topicId();
            if (topicId != null && (current.getCourseId() == null
                    || topicOf(userId, current.getCourseId(), topicId) == null)) {
                throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
            }
        }
        // "NONE"은 도움 수준을 지운다(생략은 그대로 둔다).
        String help = kind == FactKind.RESOLVED
                ? (edit.help() != null ? helpLevel(edit.help()) : current.getHelpLevel()) : null;
        String label = kind == FactKind.EXAM_SCOPE
                ? (edit.label() != null ? label(edit.label()) : current.getFactLabel()) : null;
        int rows = userContextMapper.updateStatusIfIn(contextId, userId, List.of("ACTIVE", "STALE"),
                UserContextStatus.SUPERSEDED);
        if (rows == 0) {
            throw new ConflictException(ErrorCode.CONTEXT_ALREADY_CHANGED);
        }
        LocalDateTime now = LocalDateTime.now();
        if (kind != null && kind.singleCurrent() && current.getCourseId() != null) {
            // 사용자가 지금 고친 진도·시험 범위가 현재 값이다. 다른 살아 있는 같은 상태는 대체한다.
            for (UserContext other : sameCurrent(userId, current.getCourseId(), kind, label)) {
                userContextMapper.supersede(other.getContextId(), userId);
            }
        }
        UserContext next = UserContext.builder()
                .userId(userId).content(text).contentKey(contentKey(text)).status(UserContextStatus.ACTIVE)
                .sourceType(ContextSourceType.USER_EDITED).evidenceType(ContextEvidenceType.STATED)
                .factKind(kind).factLabel(label).helpLevel(help)
                .courseId(current.getCourseId()).topicId(topicId).sectionId(current.getSectionId())
                .scopeStart(current.getScopeStart()).scopeEnd(current.getScopeEnd())
                .sourceMessageId(current.getSourceMessageId()).supersedesContextId(contextId)
                .confirmedAt(now).saidAt(now).build();
        userContextMapper.insert(next);
        UserContext saved = userContextMapper.findByIdAndUserId(next.getContextId(), userId);
        // 옛 id를 근거로 든 초안이 오래된 것이다.
        return new Change(toResponse(saved, courseTitles(userId)), draftsCiting(userId, contextId));
    }

    /** AI 추정을 "맞아요"로 확인한다. */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Change confirm(Long userId, Long contextId) {
        UserContext current = owned(userId, contextId);
        if (current.getCourseId() != null) {
            lockCourse(userId, current.getCourseId());
        }
        if (userContextMapper.confirmInferred(contextId, userId, LocalDateTime.now()) == 0) {
            throw new ConflictException(ErrorCode.CONTEXT_ALREADY_CHANGED);
        }
        FactKind kind = current.getFactKind();
        if (kind != null && kind.singleCurrent() && current.getCourseId() != null) {
            // 확인한 값이 현재 진도·시험 범위다.
            for (UserContext other : sameCurrent(userId, current.getCourseId(), kind, current.getFactLabel())) {
                if (!other.getContextId().equals(contextId)) {
                    userContextMapper.supersede(other.getContextId(), userId);
                }
            }
        }
        return new Change(toResponse(userContextMapper.findByIdAndUserId(contextId, userId), courseTitles(userId)),
                draftsCiting(userId, contextId));
    }

    /** 지우기(철회). 행은 남는다 — 같은 내용이 다시 저장되는 것을 막는 근거다. */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Change withdraw(Long userId, Long contextId) {
        UserContext current = owned(userId, contextId);
        if (current.getCourseId() != null) {
            // 자동 저장과 같은 잠금 — 철회 검사와 저장 사이에 철회가 끼어 같은 내용이 다시 들어가지 않게.
            lockCourse(userId, current.getCourseId());
        }
        if (userContextMapper.withdraw(contextId, userId, LocalDateTime.now()) == 0) {
            throw new ConflictException(ErrorCode.CONTEXT_ALREADY_CHANGED);
        }
        current.setStatus(UserContextStatus.WITHDRAWN);
        return new Change(toResponse(current, courseTitles(userId)), draftsCiting(userId, contextId));
    }

    /**
     * 상담 턴의 자동 저장. 실패해도 턴은 실패하지 않는다(호출자가 잡는다).
     */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public List<UserContextResponse> autoSave(Long userId, Long sourceMessageId, String userMessage, List<AutoSave> ops,
                                              int maxPerTurn) {
        return autoSave(userId, sourceMessageId, userMessage, ops, maxPerTurn, null, null);
    }

    /**
     * 상담 턴의 자동 저장.
     *
     * <p>서버가 지키는 것:
     * <ol>
     *   <li>STATED·SELF_REPORT는 인용(quote)이 이번 사용자 발화 안에 있고, 문장 속 숫자도 전부 사용자 발화에 있어야 한다 —
     *       아니면 AI의 추정으로 낮춘다. AI의 말이 사용자 동의 없이 사용자 상태가 되지 않는다.</li>
     *   <li>단원 연결은 그 과목의 살아 있는 단원만. 사용자 말로 인정하려면 사용자가 그 단원을 실제로 가리켰어야 한다.</li>
     *   <li>사용자가 지운 것과 같은 내용(과목·단원·본문 키)은 이력 전체에서 막는다. 같은 것이 이미 있으면 새로 만들지 않고,
     *       AI 추정이던 것을 사용자가 직접 말했으면 근거만 올린다.</li>
     *   <li>진도·시험 범위는 과목(·시험 이름)당 하나. 확인된 사용자 발화(STATED)가 더 늦게 한 말일 때만 옛 값을 대체한다 —
     *       늦게 끝난 옛 턴이 사용자의 정정을 되돌리지 못한다. 추정은 확인된 값을 덮지 않는다.</li>
     *   <li>막힘은 해결이 그 막힘을 명시적으로 가리키고(resolves) 사용자가 말한 것일 때만 닫힌다.</li>
     *   <li>프로젝트는 사용자의 것만. 남의 과목 id가 붙은 것은 저장하지 않는다.</li>
     * </ol>
     *
     * @param saidAt          근거 발화 시각(사용자 메시지 created_at). null이면 지금
     * @param defaultCourseId 과목 대화의 과목. 과목을 적지 않은 과목 사실(진도·시험·막힘·해결)에 쓴다
     * @return 이번 턴에 새로 저장(또는 근거를 올린) 것
     */
    /*
     * READ COMMITTED: 과목 행을 잠근 뒤 읽는 기억이 잠금을 기다리는 사이 커밋된 다른 턴의 저장을 봐야 한다. 기본(REPEATABLE
     * READ)이면 잠금 전 첫 조회 때의 스냅숏을 계속 읽어, 동시에 저장한 두 진도가 서로를 못 보고 둘 다 남는다(DB 테스트로 재현).
     */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public List<UserContextResponse> autoSave(Long userId, Long sourceMessageId, String userMessage, List<AutoSave> ops,
                                              int maxPerTurn, LocalDateTime saidAt, Long defaultCourseId) {
        if (ops == null || ops.isEmpty()) {
            return List.of();
        }
        LocalDateTime said = saidAt == null ? LocalDateTime.now() : saidAt;
        Map<Long, String> titles = courseTitles(userId);
        List<AutoSave> picked = lastOfSingleCurrent(ops);
        // 과목 id 순서로 잠근다 — 두 대화가 동시에 저장해도 "과목당 하나"가 깨지지 않고 교착도 없다.
        java.util.TreeSet<Long> courses = new java.util.TreeSet<>();
        for (AutoSave op : picked) {
            Long c = courseOf(op, titles, defaultCourseId);
            if (c != null) {
                courses.add(c);
            }
        }
        courses.forEach(c -> lockCourse(userId, c));

        // 예전 철회 행(본문 키 없음)은 그 사용자의 것 전부를 같은 정규화로 비교한다 — 최근 몇 건으로 자르지 않는다.
        List<UserContext> withdrawnLegacy = userContextMapper.findLegacyWithdrawnByUserId(userId);
        String saidKey = key(userMessage);
        List<UserContextResponse> saved = new ArrayList<>();
        for (AutoSave op : picked) {
            if (saved.size() >= maxPerTurn) {
                break;
            }
            String text = normalize(op.text());
            if (text == null) {
                continue;
            }
            if (op.courseId() != null && !titles.containsKey(op.courseId())) {
                log.info("상담 자동 저장: 사용자의 프로젝트가 아니라 저장하지 않는다. userId={}", userId);
                continue;
            }
            Long courseId = courseOf(op, titles, defaultCourseId);
            FactKind kind = op.kind();
            String k = contentKey(text);

            ContextEvidenceType type = op.evidenceType() == null ? ContextEvidenceType.INFERRED : op.evidenceType();
            if (type == ContextEvidenceType.OBSERVED) {
                type = ContextEvidenceType.INFERRED; // 관찰은 서버가 실행 기록에서만 만든다. 모델이 주장할 수 없다.
            }
            if (type != ContextEvidenceType.INFERRED) {
                String quote = key(op.quote());
                if (quote.length() < 2 || !saidKey.contains(quote) || !StudyFactRules.numbersBackedBy(text, userMessage)) {
                    type = ContextEvidenceType.INFERRED;
                }
            }
            if (type != ContextEvidenceType.INFERRED && !StudyFactRules.kindBackedBy(kind, op.quote())) {
                // 인용에 그 종류의 단서가 없다(예: "어려워"를 진도로 적음) — 사용자 말로 그 종류가 되지 않는다.
                type = ContextEvidenceType.INFERRED;
            }
            // 해결 대상(막힘)은 먼저 정한다 — 단원은 대상에서 승계하고, 숫자 대조는 대상 단원 제목까지 본다.
            UserContext target = null;
            com.jungwoo.project.memo.learning.domain.CourseTopic targetTopic = null;
            if (kind == FactKind.RESOLVED && op.resolves() != null && courseId != null) {
                target = userContextMapper.findActiveAndStaleByCourse(userId, courseId).stream()
                        .filter(e -> e.getContextId().equals(op.resolves()) && e.getFactKind() == FactKind.DIFFICULTY)
                        .findFirst().orElse(null);
                if (target != null && target.getTopicId() != null) {
                    targetTopic = topicOf(userId, courseId, target.getTopicId());
                }
            }
            if (type == ContextEvidenceType.INFERRED && targetTopic != null && op.evidenceType() != null
                    && op.evidenceType() != ContextEvidenceType.INFERRED && op.evidenceType() != ContextEvidenceType.OBSERVED) {
                // 대상 단원 번호를 문장에 보충했을 뿐이면 사용자 말로 다시 본다(인용은 여전히 발화 안에 있어야 한다).
                String quote = key(op.quote());
                if (quote.length() >= 2 && saidKey.contains(quote)
                        && StudyFactRules.numbersBackedBy(text, userMessage + " " + targetTopic.getTitle())) {
                    type = op.evidenceType();
                }
            }
            Long topicId = null;
            if (op.topicId() != null && courseId != null) {
                com.jungwoo.project.memo.learning.domain.CourseTopic topic = topicOf(userId, courseId, op.topicId());
                if (topic != null && (type == ContextEvidenceType.INFERRED
                        || StudyFactRules.mentionsTopic(userMessage, topic.getTitle(), topic.getSourceTocSeq()))) {
                    topicId = topic.getTopicId();
                }
            }
            // 시험 이름도 사용자가 실제로 쓴 말이어야 한다(모델이 지어낸 이름이 확인된 상태에 들어가지 않게).
            String rawLabel = kind == FactKind.EXAM_SCOPE ? label(op.label()) : null;
            String label = rawLabel != null && key(rawLabel).length() >= 2 && saidKey.contains(key(rawLabel)) ? rawLabel : null;
            boolean userSaid = type == ContextEvidenceType.STATED || type == ContextEvidenceType.SELF_REPORT;
            if (target != null) {
                if (!userSaid || !StudyFactRules.kindBackedBy(FactKind.RESOLVED, op.quote())
                        || (topicId != null && target.getTopicId() != null && !topicId.equals(target.getTopicId()))) {
                    target = null; // 사용자 말이 아니거나 다른 단원을 가리킨다 — 막힘을 닫지 않는다
                } else if (target.getTopicId() != null) {
                    topicId = target.getTopicId();
                }
            }
            String help = kind == FactKind.RESOLVED && userSaid ? helpLevel(op.help()) : null;

            if (userContextMapper.countWithdrawnByKey(userId, courseId, topicId, k) > 0
                    || withdrawnLegacy.stream().anyMatch(w -> key(w.getContent()).equals(key(text)))) {
                log.info("상담 자동 저장: 사용자가 지운 내용과 같아 저장하지 않는다. userId={}", userId);
                continue;
            }
            List<UserContext> live = courseId == null
                    ? userContextMapper.findActiveAndStaleByUserId(userId, null).stream()
                            .filter(c -> c.getCourseId() == null).toList()
                    : userContextMapper.findActiveAndStaleByCourse(userId, courseId);
            final Long topic = topicId;
            UserContext same = live.stream().filter(e -> key(e.getContent()).equals(key(text))
                    && (kind == null || e.getFactKind() == null || e.getFactKind() == kind)
                    && (kind != FactKind.EXAM_SCOPE || Objects.equals(key(e.getFactLabel()), key(label)))
                    && (topic == null || e.getTopicId() == null || topic.equals(e.getTopicId()))).findFirst().orElse(null);
            if (same != null) {
                UserContextResponse touched = restated(userId, same, live, kind, label, type, text, op, said,
                        sourceMessageId, titles);
                if (touched != null) {
                    saved.add(touched);
                }
                // 같은 해결을 다시 말해도 새로 가리킨 막힘은 닫는다.
                if (target != null && !target.getContextId().equals(same.getContextId())) {
                    userContextMapper.supersede(target.getContextId(), userId);
                }
                continue;
            }

            Long supersedes = null;
            if (kind != null && kind.singleCurrent() && courseId != null) {
                List<UserContext> currents = live.stream().filter(e -> e.getFactKind() == kind
                        && Objects.equals(e.getFactLabel(), label)).toList();
                if (type != ContextEvidenceType.STATED) {
                    // 추정·자기평가는 확인된 진도·시험 범위를 덮지 않는다(그런 값이 있으면 저장도 하지 않는다).
                    if (currents.stream().anyMatch(e -> e.getEvidenceType() == ContextEvidenceType.STATED)) {
                        continue;
                    }
                } else if (!StudyFactRules.assertsCurrentState(text, op.quote())) {
                    // 인용이 그 주장을 직접 담지 않는다(숫자가 인용 밖·부정·질문·가정) — 확인 전 후보로만 남기고 대체하지 않는다.
                    type = ContextEvidenceType.INFERRED;
                    if (currents.stream().anyMatch(e -> e.getEvidenceType() == ContextEvidenceType.STATED)) {
                        continue;
                    }
                } else {
                    boolean newer = currents.stream().allMatch(e -> isLater(said, sourceMessageId, e));
                    if (!newer) {
                        log.info("상담 자동 저장: 더 늦게 확인된 {}가 있어 옛 턴의 값을 저장하지 않는다. userId={}", kind, userId);
                        continue;
                    }
                    for (UserContext e : currents) {
                        if (userContextMapper.supersede(e.getContextId(), userId) > 0) {
                            supersedes = e.getContextId();
                        }
                    }
                }
            }
            if (target != null && userContextMapper.supersede(target.getContextId(), userId) > 0) {
                supersedes = target.getContextId();
            }

            LocalDate start = op.scopeStart();
            LocalDate end = op.scopeEnd();
            if (start != null && end != null && end.isBefore(start)) {
                start = null;
                end = null;
            }
            UserContext row = UserContext.builder()
                    .userId(userId).content(text).contentKey(k).status(UserContextStatus.ACTIVE)
                    .sourceType(ContextSourceType.CONSULT_AUTO).evidenceType(type).courseId(courseId)
                    .factKind(kind).factLabel(label).helpLevel(help).topicId(topicId)
                    .scopeStart(start).scopeEnd(end).sourceMessageId(sourceMessageId).supersedesContextId(supersedes)
                    .confirmedAt(type == ContextEvidenceType.INFERRED ? null : LocalDateTime.now())
                    .saidAt(said).build();
            userContextMapper.insert(row);
            UserContextResponse response = toResponse(userContextMapper.findByIdAndUserId(row.getContextId(), userId), titles);
            if (response != null && topicId != null) {
                com.jungwoo.project.memo.learning.domain.CourseTopic t = topicOf(userId, courseId, topicId);
                response.setTopicTitle(t == null ? null : t.getTitle());
            }
            saved.add(response);
        }
        return saved;
    }

    /**
     * 이미 있는 같은 내용을 다시 말했다. 새로 만들지 않되 대체 규칙은 같게 적용한다: 진도·시험 범위는 인용이 그 주장을 담고 더
     * 늦은 말일 때만 다른 현재 값을 대체하고, 추정이던 것은 근거를 올리고, 이미 확인된 것은 발화 시각을 새로 한다(그 뒤에 늦게 끝난
     * 옛 턴이 이 값을 덮지 못하게).
     *
     * @return 근거를 올렸으면 그 행(화면 알림용), 아니면 null
     */
    private UserContextResponse restated(Long userId, UserContext same, List<UserContext> live, FactKind kind, String label,
                                         ContextEvidenceType type, String text, AutoSave op, LocalDateTime said,
                                         Long sourceMessageId, Map<Long, String> titles) {
        boolean userSaid = type == ContextEvidenceType.STATED || type == ContextEvidenceType.SELF_REPORT;
        if (!userSaid) {
            return null;
        }
        FactKind k = kind != null ? kind : same.getFactKind();
        if (k != null && k.singleCurrent()) {
            if (type != ContextEvidenceType.STATED || !StudyFactRules.assertsCurrentState(text, op.quote())) {
                return null;
            }
            List<UserContext> others = live.stream().filter(e -> !e.getContextId().equals(same.getContextId())
                    && e.getFactKind() == k && Objects.equals(e.getFactLabel(), label)).toList();
            if (!others.stream().allMatch(e -> isLater(said, sourceMessageId, e))) {
                return null;
            }
            others.forEach(e -> userContextMapper.supersede(e.getContextId(), userId));
        }
        if (same.getEvidenceType() == ContextEvidenceType.INFERRED && type == ContextEvidenceType.STATED) {
            if (userContextMapper.promoteToStated(same.getContextId(), userId, said, sourceMessageId) > 0) {
                return toResponse(userContextMapper.findByIdAndUserId(same.getContextId(), userId), titles);
            }
            return null;
        }
        if (isLater(said, sourceMessageId, same)) {
            // 더 늦게 다시 말한 기간이 있으면 그 기간이 지금 기간이다("이번 주만"을 다음 주에 다시 말함).
            LocalDate start = op.scopeStart();
            LocalDate end = op.scopeEnd();
            if (start != null && end != null && end.isBefore(start)) {
                start = null;
                end = null;
            }
            userContextMapper.touchSaid(same.getContextId(), userId, said, sourceMessageId, start, end);
        }
        return null;
    }

    /** 진도·시험 범위는 한 턴에 여러 개가 와도 (과목·종류·시험 이름)마다 마지막 것만 — 한 말 안의 최종 상태다. */
    private static List<AutoSave> lastOfSingleCurrent(List<AutoSave> ops) {
        List<AutoSave> out = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) {
            AutoSave op = ops.get(i);
            if (op == null) {
                continue;
            }
            if (op.kind() != null && op.kind().singleCurrent()) {
                boolean laterSame = false;
                for (int j = i + 1; j < ops.size(); j++) {
                    AutoSave later = ops.get(j);
                    if (later != null && later.kind() == op.kind() && Objects.equals(later.courseId(), op.courseId())
                            && Objects.equals(label(later.label()), label(op.label()))) {
                        laterSame = true;
                        break;
                    }
                }
                if (laterSame) {
                    continue;
                }
            }
            out.add(op);
        }
        return out;
    }

    private static Long courseOf(AutoSave op, Map<Long, String> titles, Long defaultCourseId) {
        if (op.courseId() != null) {
            return titles.containsKey(op.courseId()) ? op.courseId() : null;
        }
        boolean courseFact = op.kind() == FactKind.PROGRESS || op.kind() == FactKind.EXAM_SCOPE
                || op.kind() == FactKind.DIFFICULTY || op.kind() == FactKind.RESOLVED;
        return courseFact && defaultCourseId != null && titles.containsKey(defaultCourseId) ? defaultCourseId : null;
    }

    private List<UserContext> sameCurrent(Long userId, Long courseId, FactKind kind, String label) {
        return userContextMapper.findActiveAndStaleByCourse(userId, courseId).stream()
                .filter(e -> e.getFactKind() == kind && Objects.equals(e.getFactLabel(), label)).toList();
    }

    /**
     * 새 메시지 사실이 기존 행보다 늦게 한 말인가. 시각은 초 단위라 같을 수 있다 — 메시지끼리는 메시지 번호가 큰 쪽이 늦고,
     * 사용자 수정(USER_EDITED)과 같은 초면 수정이 이긴다(명시적 정정).
     */
    static boolean isLater(LocalDateTime said, Long messageId, UserContext existing) {
        LocalDateTime other = saidAtOf(existing);
        if (said.isAfter(other)) {
            return true;
        }
        if (said.isEqual(other) && existing.getSourceType() != ContextSourceType.USER_EDITED) {
            return messageId != null && existing.getSourceMessageId() != null
                    && messageId > existing.getSourceMessageId();
        }
        return false;
    }

    private static LocalDateTime saidAtOf(UserContext c) {
        if (c.getSaidAt() != null) {
            return c.getSaidAt();
        }
        return c.getUpdatedAt() != null ? c.getUpdatedAt() : c.getCreatedAt() != null ? c.getCreatedAt() : LocalDateTime.MIN;
    }

    private void lockCourse(Long userId, Long courseId) {
        courseMapper.findByIdAndUserIdForUpdate(courseId, userId);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.jungwoo.project.memo.learning.CourseTopicMapper topicMapper;

    void setTopicMapper(com.jungwoo.project.memo.learning.CourseTopicMapper topicMapper) {
        this.topicMapper = topicMapper;
    }

    /** 그 과목의 살아 있는 단원. 아니면 null. */
    private com.jungwoo.project.memo.learning.domain.CourseTopic topicOf(Long userId, Long courseId, Long topicId) {
        if (topicMapper == null || topicId == null) {
            return null;
        }
        com.jungwoo.project.memo.learning.domain.CourseTopic topic = topicMapper.findByIdAndUserId(topicId, userId);
        if (topic == null || !courseId.equals(topic.getCourseId())
                || topic.getStatus() != com.jungwoo.project.memo.learning.domain.TopicStatus.ACTIVE) {
            return null;
        }
        return topic;
    }

    /** 정규화 본문의 SHA-256(16진수 64자). 길이와 무관하게 잘리지 않는 키다. */
    static String contentKey(String text) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(key(text).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String label(String raw) {
        String v = normalize(raw);
        return v == null ? null : v.length() > 40 ? v.substring(0, 40) : v;
    }

    private static String helpLevel(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.strip().toUpperCase(Locale.ROOT);
        return "SOLO".equals(v) || "GUIDED".equals(v) ? v : null; // "NONE" 등은 지움
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
                    .userId(userId).content(cut(content)).contentKey(contentKey(cut(content))).status(UserContextStatus.ACTIVE)
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
                .factKind(context.getFactKind())
                .factLabel(context.getFactLabel())
                .helpLevel(context.getHelpLevel())
                .saidAt(context.getSaidAt())
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
    public static String key(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}·…“”‘’]+", "");
    }
}
