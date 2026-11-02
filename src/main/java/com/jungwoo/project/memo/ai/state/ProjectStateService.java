package com.jungwoo.project.memo.ai.state;

import com.jungwoo.project.memo.ai.UserContextMapper;
import com.jungwoo.project.memo.ai.domain.ContextEvidenceType;
import com.jungwoo.project.memo.ai.domain.ContextSourceType;
import com.jungwoo.project.memo.ai.domain.FactKind;
import com.jungwoo.project.memo.ai.domain.UserContext;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.course.CourseMapper;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.course.textbook.TextbookFacts;
import com.jungwoo.project.memo.learning.CourseTopicMapper;
import com.jungwoo.project.memo.learning.correction.CourseCorrectionMapper;
import com.jungwoo.project.memo.learning.correction.CourseScopeExclusion;
import com.jungwoo.project.memo.learning.correction.TopicClassProgress;
import com.jungwoo.project.memo.learning.domain.CourseTopic;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 한 프로젝트의 "확인된 현재 상태" — 교재·수업 진도·실제 수업 정정·시험 범위·막힌 곳·해결·목표·AI 추정.
 *
 * <p>화면(사실 카드)·상담·자료 선택·계획 생성이 <b>같은 조회</b>를 쓴다. 화면에서 본 것과 AI가 받는 것이 달라지지 않게 하고,
 * "이미 말한 교재·진도를 다시 묻지 않는다"를 어느 대화·어느 생성 경로에서나 같은 근거로 지키게 한다.
 *
 * <p>렌더링은 고정 상한(줄 수·줄 길이) 안에서 줄 단위로 줄인다(문장 중간을 자르지 않는다). 실제로 렌더링한 기억 id를 함께
 * 돌려준다 — 장기 컨텍스트에서 중복을 뺄 때 그 id만 뺀다(렌더링되지 않은 기억이 양쪽에서 모두 사라지지 않게).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectStateService {

    public static final int MAX_LINES = 14;
    public static final int MAX_LINE_CHARS = 120;
    static final int MAX_CLASS_PROGRESS = 5;
    static final int RESOLVED_DAYS = 30;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("M/d");

    private final CourseMapper courseMapper;
    private final UserContextMapper userContextMapper;
    private final CourseTopicMapper topicMapper;

    @Autowired(required = false)
    private CourseCorrectionMapper correctionMapper;

    /** 사진 문맥으로 단원을 채운 기억의 연결 상태(추정 표시). 없으면 표시 없이. */
    @Autowired(required = false)
    private com.jungwoo.project.memo.learning.TopicMaterialLinkMapper topicMaterialLinkMapper;

    /** 사진 문맥으로 단원을 채운 기억인데 그 사진의 단원이 아직 서버 추정이면 붙는 표시. */
    public static final String PHOTO_GUESS_MARK = " (사진 단원 추정)";

    /** 기억 한 줄. */
    public record Fact(Long contextId, FactKind kind, String label, String text, Long topicId, String topicTitle,
                       Integer topicTocSeq, ContextEvidenceType evidenceType, ContextSourceType sourceType,
                       String help, LocalDateTime saidAt, LocalDateTime updatedAt, String status,
                       Long sourceMessageId, LocalDate scopeStart, LocalDate scopeEnd) {

        public Fact(Long contextId, FactKind kind, String label, String text, Long topicId, String topicTitle,
                    Integer topicTocSeq, ContextEvidenceType evidenceType, ContextSourceType sourceType,
                    String help, LocalDateTime saidAt, LocalDateTime updatedAt, String status, Long sourceMessageId) {
            this(contextId, kind, label, text, topicId, topicTitle, topicTocSeq, evidenceType, sourceType, help, saidAt,
                    updatedAt, status, sourceMessageId, null, null);
        }

        /** 확인 전 AI 추정인가. */
        public boolean inferred() {
            return evidenceType == ContextEvidenceType.INFERRED;
        }
    }

    public record ClassLine(Long topicId, String topicTitle, Integer classSeq, Integer weekNo) {
    }

    public record Exclusion(Long topicId, String topicTitle, String label) {
    }

    /**
     * @param textbookLine 교재 한 줄(교재 칸 그대로, 출처 포함). 없으면 null
     * @param facts        종류 있는 기억(살아 있는 것) — 정렬: 종류 순, 같은 종류는 최근 말한 순
     * @param fingerprint  상태 지문(기억·정정·제외·교재 판). 초안 최신성·자료 선택 재사용 판단에 쓴다
     */
    public record State(Long courseId, String courseTitle, String textbookLine, List<Fact> facts,
                        List<ClassLine> classProgress, List<Exclusion> exclusions, String fingerprint, String status) {

        public State(Long courseId, String courseTitle, String textbookLine, List<Fact> facts,
                     List<ClassLine> classProgress, List<Exclusion> exclusions, String fingerprint) {
            this(courseId, courseTitle, textbookLine, facts, classProgress, exclusions, fingerprint, null);
        }

        public boolean isEmpty() {
            return textbookLine == null && facts.isEmpty() && classProgress.isEmpty() && exclusions.isEmpty();
        }
    }

    /** 렌더링 결과. contextIds는 실제로 글에 실린 기억만. */
    public record Rendered(String text, List<Long> contextIds) {
        public static final Rendered EMPTY = new Rendered("", List.of());
    }

    /** 줄마다 출처 표시를 붙이는 곳(계획 생성은 근거 번호를, 상담은 그대로). */
    @FunctionalInterface
    public interface LineMarker {
        String mark(Fact fact, String line);

        LineMarker PLAIN = (fact, line) -> line;
    }

    /** 사용자 소유 과목만. 아니면 NotFound. */
    @Transactional(readOnly = true)
    public State load(Long userId, Long courseId) {
        Course course = courseMapper.findByIdAndUserId(courseId, userId);
        if (course == null) {
            throw new NotFoundException(ErrorCode.COURSE_NOT_FOUND);
        }
        return build(userId, course);
    }

    /** 조회 실패는 null(상담·계획은 블록 없이 계속한다). */
    @Transactional(readOnly = true)
    public State loadQuietly(Long userId, Long courseId) {
        try {
            Course course = courseMapper.findByIdAndUserId(courseId, userId);
            return course == null ? null : build(userId, course);
        } catch (Exception e) {
            log.warn("프로젝트 상태를 읽지 못했다 — 블록 없이 진행한다. courseId={}, {}", courseId, e.getClass().getSimpleName());
            return null;
        }
    }

    /** 그 과목의 살아 있는 단원(트리 순서). 소유 확인은 load가 먼저 한다. */
    @Transactional(readOnly = true)
    public List<CourseTopic> topics(Long userId, Long courseId) {
        return topicMapper.findActiveByCourseIdAndUserId(courseId, userId);
    }

    /** 종류 있는 기억이 있는 과목, 최근에 말한 순. */
    @Transactional(readOnly = true)
    public List<Long> recentFactCourseIds(Long userId, int limit) {
        try {
            return userContextMapper.findRecentFactCourseIds(userId, limit);
        } catch (Exception e) {
            return List.of();
        }
    }

    State build(Long userId, Course course) {
        Long courseId = course.getCourseId();
        Map<Long, CourseTopic> topics = new HashMap<>();
        for (CourseTopic t : topicMapper.findActiveByCourseIdAndUserId(courseId, userId)) {
            topics.put(t.getTopicId(), t);
        }
        LocalDateTime resolvedSince = LocalDate.now().minusDays(RESOLVED_DAYS).atStartOfDay();
        List<Fact> facts = new ArrayList<>();
        for (UserContext c : userContextMapper.findActiveAndStaleByCourse(userId, courseId)) {
            if (c.getFactKind() == null) {
                continue; // 종류 없는 예전 기억은 장기 컨텍스트로 간다
            }
            if (c.getFactKind() == FactKind.RESOLVED && saidAt(c).isBefore(resolvedSince)) {
                continue;
            }
            if (c.getScopeEnd() != null && c.getScopeEnd().isBefore(LocalDate.now())) {
                continue; // 적용 기간이 지난 것은 지금 상태가 아니다
            }
            CourseTopic topic = c.getTopicId() == null ? null : topics.get(c.getTopicId());
            String topicTitle = topic == null ? null : topic.getTitle() + (photoGuess(userId, c) ? PHOTO_GUESS_MARK : "");
            facts.add(new Fact(c.getContextId(), c.getFactKind(), c.getFactLabel(), c.getContent(),
                    topic == null ? null : topic.getTopicId(), topicTitle,
                    topic == null ? null : topic.getSourceTocSeq(), c.getEvidenceType(), c.getSourceType(),
                    c.getHelpLevel(), saidAt(c), c.getUpdatedAt(), c.getStatus() == null ? null : c.getStatus().name(),
                    c.getSourceMessageId(), c.getScopeStart(), c.getScopeEnd()));
        }
        facts.sort(Comparator.comparing((Fact f) -> f.kind().ordinal())
                .thenComparing(Fact::saidAt, Comparator.reverseOrder()));

        List<ClassLine> classLines = new ArrayList<>();
        List<Exclusion> exclusions = new ArrayList<>();
        if (correctionMapper != null) {
            for (TopicClassProgress p : correctionMapper.findClassProgress(courseId, userId)) {
                CourseTopic t = topics.get(p.getTopicId());
                classLines.add(new ClassLine(p.getTopicId(), t == null ? null : t.getTitle(), p.getClassSeq(), p.getWeekNo()));
            }
            for (CourseScopeExclusion e : correctionMapper.findActiveExclusions(courseId, userId)) {
                CourseTopic t = topics.get(e.getTopicId());
                exclusions.add(new Exclusion(e.getTopicId(), t == null ? null : t.getTitle(), e.getLabel()));
            }
        }
        String textbook = TextbookFacts.identity(course.getTextbookTitle(), course.getTextbookIsbn(),
                course.getTextbookEdition(), course.getTextbookPublisher(), course.getTextbookAuthor(),
                course.getTextbookInfoSource());
        return new State(courseId, course.getTitle(), textbook, facts, classLines, exclusions,
                fingerprint(course, facts, classLines, exclusions),
                course.getStatus() == null ? null : course.getStatus().name());
    }

    private static LocalDateTime saidAt(UserContext c) {
        return c.getSaidAt() != null ? c.getSaidAt()
                : c.getUpdatedAt() != null ? c.getUpdatedAt() : c.getCreatedAt() != null ? c.getCreatedAt() : LocalDateTime.MIN;
    }

    static String fingerprint(Course course, List<Fact> facts, List<ClassLine> classLines, List<Exclusion> exclusions) {
        StringBuilder sb = new StringBuilder();
        sb.append("tb:").append(course.getTextbookVersion()).append('|')
                .append(course.getTextbookTitle()).append('|').append(course.getTextbookIsbn()).append('\n');
        facts.stream().sorted(Comparator.comparing(Fact::contextId)).forEach(f -> sb.append(f.contextId()).append(':')
                .append(f.kind()).append(':').append(f.topicId()).append(':').append(f.evidenceType()).append(':')
                .append(f.help()).append(':').append(f.status()).append(':').append(f.updatedAt()).append('\n'));
        classLines.forEach(c -> sb.append("cp:").append(c.topicId()).append(':').append(c.classSeq()).append(':')
                .append(c.weekNo()).append('\n'));
        exclusions.forEach(e -> sb.append("ex:").append(e.topicId()).append(':').append(e.label()).append('\n'));
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h).substring(0, 16);
        } catch (Exception e) {
            return Integer.toHexString(sb.toString().hashCode());
        }
    }

    // ===== 렌더링 =====

    /** 출처 라벨(서버가 붙인다 — 모델이 정하지 않는다). */
    public static String sourceLabel(Fact f) {
        if (f.sourceType() == ContextSourceType.USER_EDITED) {
            return "사용자가 고침";
        }
        if (f.evidenceType() == null) {
            return "사용자가 말함";
        }
        return switch (f.evidenceType()) {
            case STATED -> "사용자가 말함";
            case SELF_REPORT -> "자기평가";
            case OBSERVED -> "실행 기록";
            case INFERRED -> "AI 추정 — 확인 전";
        };
    }

    /** 이 기억의 단원이 사진 문맥에서 왔고, 그 사진의 연결이 아직 서버 추정(PHOTO_GUESS)인가. */
    private boolean photoGuess(Long userId, UserContext c) {
        if (c.getTopicPhotoId() == null || topicMaterialLinkMapper == null) {
            return false;
        }
        try {
            return topicMaterialLinkMapper.findActiveByMaterialId(c.getTopicPhotoId(), userId).stream()
                    .anyMatch(l -> java.util.Objects.equals(l.getTopicId(), c.getTopicId())
                            && l.getOrigin() == com.jungwoo.project.memo.learning.domain.TopicLinkOrigin.PHOTO_GUESS);
        } catch (Exception e) {
            return false;
        }
    }

    /** 단원 표시: "#12 Unit 3 I have to … (교재 목차 3번째)". */
    static String topicRef(Fact f) {
        if (f.topicId() == null) {
            return "";
        }
        String title = f.topicTitle() == null ? "" : " " + f.topicTitle();
        return "#" + f.topicId() + title + (f.topicTocSeq() == null ? "" : " (교재 목차 " + f.topicTocSeq() + "번째)");
    }

    /**
     * 상담·계획에 싣는 고정 블록(머리 줄과 규칙은 부르는 쪽이 붙인다 — {@link #HEADER}). 순서: 교재 → 수업 진도 → 실제 수업
     * 정정 → 시험 범위·범위 제외 → 막힌 곳 → 해결 → 목표·제약·선호 → AI 추정. 상한을 넘으면 추정 → 해결 → 목표류 순으로 줄
     * 단위로 뺀다. 줄은 길이 상한에서 자르지만, 범위 제외의 뜻("학습 완료가 아니다")은 머리 규칙에 있어 잘리지 않는다.
     *
     * @param maxLines 머리 줄을 뺀 내용 줄 상한
     */
    public static Rendered render(State state, int maxLines, LineMarker marker) {
        if (state == null || state.isEmpty()) {
            return Rendered.EMPTY;
        }
        List<Line> lines = new ArrayList<>();
        if (state.textbookLine() != null) {
            lines.add(new Line(0, null, "교재: " + state.textbookLine()));
        }
        for (Fact f : state.facts()) {
            if (f.kind() == FactKind.PROGRESS && !f.inferred()) {
                lines.add(new Line(1, f, "수업 진도: " + f.text() + " (" + when(f) + ")"));
            }
        }
        int shown = 0;
        for (ClassLine c : state.classProgress()) {
            if (shown++ >= MAX_CLASS_PROGRESS) {
                break;
            }
            lines.add(new Line(2, null, "실제 수업 정정: #" + c.topicId() + (c.topicTitle() == null ? "" : " " + c.topicTitle())
                    + (c.weekNo() == null ? "" : " → " + c.weekNo() + "주차") + (c.classSeq() == null ? "" : " (" + c.classSeq() + "번째로 다룸)")));
        }
        for (Fact f : state.facts()) {
            if (f.kind() == FactKind.EXAM_SCOPE && !f.inferred()) {
                lines.add(new Line(3, f, "시험 범위" + (f.label() == null ? "" : "(" + f.label() + ")") + ": " + f.text()
                        + " (" + when(f) + ")"));
            }
        }
        for (Exclusion e : state.exclusions()) {
            lines.add(new Line(3, null, "범위에서 뺀 것: #" + e.topicId() + (e.topicTitle() == null ? "" : " " + e.topicTitle())
                    + (e.label() == null ? "" : " (" + e.label() + ")")));
        }
        for (Fact f : state.facts()) {
            if (f.kind() == FactKind.DIFFICULTY && !f.inferred()) {
                lines.add(new Line(4, f, "막힌 곳 m" + f.contextId() + (f.topicId() == null ? "" : " [" + topicRef(f) + "]")
                        + ": " + f.text() + " (" + when(f) + ")"));
            }
        }
        for (Fact f : state.facts()) {
            if (f.kind() == FactKind.RESOLVED && !f.inferred()) {
                String help = "GUIDED".equals(f.help()) ? "도움받아 해결" : "SOLO".equals(f.help()) ? "혼자 해결" : "해결";
                lines.add(new Line(5, f, help + (f.topicId() == null ? "" : " [" + topicRef(f) + "]") + ": " + f.text()
                        + " (" + when(f) + ")"));
            }
        }
        for (Fact f : state.facts()) {
            if ((f.kind() == FactKind.GOAL || f.kind() == FactKind.CONSTRAINT || f.kind() == FactKind.PREFERENCE
                    || f.kind() == FactKind.OTHER) && !f.inferred()) {
                lines.add(new Line(6, f, f.kind().label() + ": " + f.text() + " (" + when(f) + ")"));
            }
        }
        for (Fact f : state.facts()) {
            if (f.inferred()) {
                lines.add(new Line(7, f, "AI 추정(확인 전) " + f.kind().label() + (f.topicId() == null ? "" : " [" + topicRef(f) + "]")
                        + ": " + f.text() + " (" + DAY.format(f.saidAt()) + ")"));
            }
        }
        // 넘치면 우선순위가 낮은 묶음부터(추정 → 해결 → 목표류 → 실제 수업 정정 …) 그 묶음의 뒤에서 한 줄씩 뺀다.
        while (lines.size() > maxLines) {
            int drop = -1;
            for (int rank : new int[]{7, 5, 6, 2, 4, 3, 1}) {
                for (int i = lines.size() - 1; i >= 0; i--) {
                    if (lines.get(i).rank == rank) {
                        drop = i;
                        break;
                    }
                }
                if (drop >= 0) {
                    break;
                }
            }
            if (drop < 0) {
                break;
            }
            lines.remove(drop);
        }
        StringBuilder sb = new StringBuilder();
        List<Long> ids = new ArrayList<>();
        for (Line l : lines) {
            String text = cut(l.text);
            sb.append("- ").append(l.fact == null ? text : marker.mark(l.fact, text)).append('\n');
            if (l.fact != null) {
                ids.add(l.fact.contextId());
            }
        }
        return new Rendered(sb.toString(), ids);
    }

    /** 블록 머리와 읽는 규칙. 상담·계획이 같은 문장을 쓴다. */
    public static final String HEADER = "[이 프로젝트에서 확인된 상태 — 서버가 저장한 사실. 여기 있는 교재·수업 진도·시험 범위는 "
            + "다시 묻지 않는다. 괄호는 언제·누가 말했는지다. \"AI 추정 — 확인 전\"은 사실이 아니다(가정으로만 쓰고, 쓰기 전 한 번 "
            + "확인할 수 있다). 수업 진도(수업에서 나간 곳)와 막힘·해결(내 이해)은 다른 사실이다. \"범위에서 뺀 것\"은 계획에 넣지 "
            + "않지만 학습을 마쳤다는 뜻이 아니다. m번호는 막힌 곳의 번호다]";

    private record Line(int rank, Fact fact, String text) {
    }

    private static String when(Fact f) {
        String until = f.scopeEnd() == null ? "" : ", " + DAY.format(f.scopeEnd()) + "까지만";
        return (f.saidAt() == null ? "" : DAY.format(f.saidAt()) + " ") + sourceLabel(f) + until;
    }

    private static String cut(String s) {
        String flat = s.replaceAll("\\s+", " ").strip();
        return flat.length() > MAX_LINE_CHARS ? flat.substring(0, MAX_LINE_CHARS - 1) + "…" : flat;
    }
}
