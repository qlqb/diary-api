package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.domain.ContextEvidenceType;
import com.jungwoo.project.memo.ai.domain.ContextSourceType;
import com.jungwoo.project.memo.ai.domain.FactKind;
import com.jungwoo.project.memo.ai.domain.UserContext;
import com.jungwoo.project.memo.ai.domain.UserContextStatus;
import com.jungwoo.project.memo.ai.dto.UserContextResponse;
import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.course.CourseMapper;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.learning.CourseTopicMapper;
import com.jungwoo.project.memo.learning.domain.CourseTopic;
import com.jungwoo.project.memo.learning.domain.TopicStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 학습 기억(종류·단원·대체) 정책 — 계획 v3 §3.2·§8.
 *
 * <p>사용자가 매번 카드를 쓰지 않아도 상담이 기억을 채우되, (1) AI의 말이 사용자 사실이 되지 않고 (2) 늦게 끝난 옛 턴이나 추정이
 * 사용자의 정정을 덮지 않고 (3) 같은 단원의 다른 막힘이 잘못 닫히지 않는다.
 */
class StudyMemoryPolicyTest {

    private static final long USER = 7L;
    private static final long COURSE = 940L;
    private static final long UNIT3 = 503L;
    private static final long UNIT4 = 504L;
    private static final long UNIT1 = 501L;
    private static final long UNIT10 = 510L;
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 5, 20, 0, 0);

    private final UserContextMapper mapper = mock(UserContextMapper.class);
    private final CourseMapper courseMapper = mock(CourseMapper.class);
    private final CourseTopicMapper topicMapper = mock(CourseTopicMapper.class);
    private final UserContextService service = new UserContextService(mapper, courseMapper, mock(AiProposalMapper.class));

    private final List<UserContext> rows = new ArrayList<>();
    private long nextId = 100;

    @BeforeEach
    void setUp() {
        service.setTopicMapper(topicMapper);
        when(courseMapper.findByUserIdAndStatus(USER, "ACTIVE"))
                .thenReturn(List.of(Course.builder().courseId(COURSE).userId(USER).title("영어회화").build()));
        when(courseMapper.findByUserIdAndStatus(USER, "ARCHIVED")).thenReturn(List.of());
        topic(UNIT1, "Unit 1 What's your name?", 1);
        topic(UNIT3, "Unit 3 I have to make hotel reservations", 3);
        topic(UNIT4, "Unit 4 Did you have a good weekend?", 4);
        topic(UNIT10, "Unit 10 What's your name?", 10);

        when(mapper.findActiveAndStaleByUserId(eq(USER), any())).thenAnswer(inv -> live(null, false));
        when(mapper.findActiveAndStaleByCourse(USER, COURSE)).thenAnswer(inv -> live(COURSE, true));
        when(mapper.findWithdrawnByUserId(eq(USER), anyInt())).thenReturn(List.of());
        when(mapper.findLegacyWithdrawnByUserId(USER)).thenAnswer(inv -> rows.stream()
                .filter(r -> r.getStatus() == UserContextStatus.WITHDRAWN && r.getContentKey() == null).toList());
        when(mapper.countWithdrawnByKey(eq(USER), any(), any(), anyString())).thenAnswer(inv -> (int) rows.stream()
                .filter(r -> r.getStatus() == UserContextStatus.WITHDRAWN
                        && Objects.equals(r.getCourseId(), inv.getArgument(1))
                        && (inv.getArgument(2) == null || r.getTopicId() == null || inv.getArgument(2).equals(r.getTopicId()))
                        && inv.getArgument(3).equals(r.getContentKey())).count());
        doAnswer(inv -> {
            UserContext c = inv.getArgument(0);
            c.setContextId(nextId++);
            rows.add(c);
            return null;
        }).when(mapper).insert(any());
        when(mapper.findByIdAndUserId(any(), eq(USER))).thenAnswer(inv -> byId(inv.getArgument(0)));
        when(mapper.supersede(anyLong(), eq(USER))).thenAnswer(inv -> setStatus(inv.getArgument(0), UserContextStatus.SUPERSEDED));
        when(mapper.updateStatusIfIn(anyLong(), eq(USER), any(), any())).thenAnswer(inv ->
                setStatus(inv.getArgument(0), inv.getArgument(3)));
        when(mapper.touchSaid(anyLong(), eq(USER), any(), any(), any(), any())).thenAnswer(inv -> {
            UserContext r = byId(inv.getArgument(0));
            r.setSaidAt(inv.getArgument(2));
            r.setSourceMessageId(inv.getArgument(3));
            if (inv.getArgument(4) != null || inv.getArgument(5) != null) {
                r.setScopeStart(inv.getArgument(4));
                r.setScopeEnd(inv.getArgument(5));
            }
            return 1;
        });
        when(mapper.promoteToStated(anyLong(), eq(USER), any(), any())).thenAnswer(inv -> {
            UserContext r = byId(inv.getArgument(0));
            if (r == null || r.getEvidenceType() != ContextEvidenceType.INFERRED) {
                return 0;
            }
            r.setEvidenceType(ContextEvidenceType.STATED);
            r.setSaidAt(inv.getArgument(2));
            return 1;
        });
    }

    private void topic(long id, String title, int seq) {
        when(topicMapper.findByIdAndUserId(eq(id), eq(USER))).thenReturn(CourseTopic.builder().topicId(id).userId(USER)
                .courseId(COURSE).title(title).sourceTocSeq(seq).status(TopicStatus.ACTIVE).build());
    }

    private List<UserContext> live(Long course, boolean byCourse) {
        return rows.stream().filter(r -> r.getStatus() == UserContextStatus.ACTIVE || r.getStatus() == UserContextStatus.STALE)
                .filter(r -> !byCourse || Objects.equals(r.getCourseId(), course)).map(r -> r).toList();
    }

    private UserContext byId(Long id) {
        return rows.stream().filter(r -> r.getContextId().equals(id)).findFirst().orElse(null);
    }

    private int setStatus(Long id, UserContextStatus status) {
        UserContext r = byId(id);
        if (r == null || (r.getStatus() != UserContextStatus.ACTIVE && r.getStatus() != UserContextStatus.STALE)) {
            return 0;
        }
        r.setStatus(status);
        return 1;
    }

    private static UserContextService.AutoSave fact(String text, ContextEvidenceType type, String quote, FactKind kind,
                                                    Long topicId, String help, Long resolves) {
        return new UserContextService.AutoSave(text, type, COURSE, null, null, quote, kind, topicId, null, help, resolves);
    }

    private List<UserContextResponse> say(String message, LocalDateTime at, long messageId, UserContextService.AutoSave... ops) {
        return service.autoSave(USER, messageId, message, List.of(ops), 4, at, COURSE);
    }

    private List<UserContext> active(FactKind kind) {
        return rows.stream().filter(r -> r.getFactKind() == kind && r.getStatus() == UserContextStatus.ACTIVE).toList();
    }

    @Test
    void 진도는_과목당_하나이고_더_늦게_확인된_사용자_말이_옛_값을_대체한다() {
        say("수업은 Unit 3까지 나갔어", T0, 1, fact("수업은 Unit 3까지 나갔다", ContextEvidenceType.STATED,
                "Unit 3까지 나갔어", FactKind.PROGRESS, null, null, null));
        say("오늘 Unit 4까지 나갔어", T0.plusMinutes(5), 2, fact("수업은 Unit 4까지 나갔다", ContextEvidenceType.STATED,
                "Unit 4까지 나갔어", FactKind.PROGRESS, null, null, null));

        assertThat(active(FactKind.PROGRESS)).singleElement()
                .satisfies(r -> assertThat(r.getContent()).contains("Unit 4"))
                .satisfies(r -> assertThat(r.getSaidAt()).isEqualTo(T0.plusMinutes(5)));
        assertThat(rows.get(0).getStatus()).isEqualTo(UserContextStatus.SUPERSEDED);
        assertThat(rows.get(1).getSupersedesContextId()).isEqualTo(rows.get(0).getContextId());
    }

    @Test
    void 추정은_확인된_진도를_덮지_못하고_부정_질문_가정은_진도가_되지_않는다() {
        say("Unit 4까지 나갔어", T0, 1, fact("수업은 Unit 4까지 나갔다", ContextEvidenceType.STATED, "Unit 4까지 나갔어",
                FactKind.PROGRESS, null, null, null));

        say("다음 주는 바빠", T0.plusMinutes(1), 2, fact("수업은 Unit 2까지 나간 것 같다", ContextEvidenceType.INFERRED, null,
                FactKind.PROGRESS, null, null, null));
        say("Unit 5까지 나가면 좋겠다", T0.plusMinutes(2), 3, fact("수업은 Unit 5까지 나갔다", ContextEvidenceType.STATED,
                "Unit 5까지 나가면 좋겠다", FactKind.PROGRESS, null, null, null));
        say("Unit 3은 아직 못 끝냈어", T0.plusMinutes(3), 4, fact("Unit 3까지 수업 완료", ContextEvidenceType.STATED,
                "Unit 3은 아직 못 끝냈어", FactKind.PROGRESS, null, null, null));

        assertThat(active(FactKind.PROGRESS)).singleElement()
                .satisfies(r -> assertThat(r.getContent()).isEqualTo("수업은 Unit 4까지 나갔다"));
    }

    @Test
    void 인용은_맞아도_사용자가_말하지_않은_숫자가_든_문장은_추정으로_낮추고_대체하지_않는다() {
        say("Unit 4까지 나갔어", T0, 1, fact("수업은 Unit 4까지 나갔다", ContextEvidenceType.STATED, "Unit 4까지 나갔어",
                FactKind.PROGRESS, null, null, null));

        // 자료 원문에 휘둘린 문장: 인용 "Unit 3"은 발화에 있지만 12는 사용자가 말하지 않았다.
        List<UserContextResponse> saved = say("Unit 3이 어려워", T0.plusMinutes(1), 2, fact("수업은 Unit 12까지 나갔다",
                ContextEvidenceType.STATED, "Unit 3", FactKind.PROGRESS, null, null, null));

        assertThat(saved).isEmpty();
        assertThat(active(FactKind.PROGRESS)).singleElement()
                .satisfies(r -> assertThat(r.getContent()).contains("Unit 4"));
    }

    @Test
    void 늦게_끝난_옛_턴은_사용자가_고친_진도를_되돌리지_못하고_같은_초면_메시지_번호와_수정이_순서를_정한다() {
        say("Unit 3까지 나갔어", T0, 10, fact("수업은 Unit 3까지 나갔다", ContextEvidenceType.STATED, "Unit 3까지 나갔어",
                FactKind.PROGRESS, null, null, null));
        UserContext first = active(FactKind.PROGRESS).get(0);
        service.edit(USER, first.getContextId(), new UserContextService.Edit("수업은 Unit 5까지 나갔다", null, null, null, null));
        UserContext edited = active(FactKind.PROGRESS).get(0);
        assertThat(edited.getSaidAt()).isNotNull();
        edited.setSaidAt(T0.plusSeconds(5)); // 고친 시각(메시지 11을 보낸 뒤)

        // PATCH 전에 보낸(said_at이 이른) 메시지의 저장이 늦게 끝났다.
        say("Unit 4까지 나갔어", T0.plusSeconds(1), 11, fact("수업은 Unit 4까지 나갔다", ContextEvidenceType.STATED,
                "Unit 4까지 나갔어", FactKind.PROGRESS, null, null, null));
        assertThat(active(FactKind.PROGRESS)).singleElement().isSameAs(edited);

        // 같은 초: 수정이 이긴다.
        edited.setSaidAt(T0.plusMinutes(10));
        say("Unit 6까지 나갔어", T0.plusMinutes(10), 12, fact("수업은 Unit 6까지 나갔다", ContextEvidenceType.STATED,
                "Unit 6까지 나갔어", FactKind.PROGRESS, null, null, null));
        assertThat(active(FactKind.PROGRESS)).singleElement().isSameAs(edited);
    }

    @Test
    void 같은_초의_두_메시지는_메시지_번호가_큰_쪽이_늦다_역순으로_끝나도() {
        // 번호 21(나중에 보냄)이 먼저 저장되고, 번호 20이 늦게 저장된다.
        say("Unit 5까지 나갔어", T0, 21, fact("수업은 Unit 5까지 나갔다", ContextEvidenceType.STATED, "Unit 5까지 나갔어",
                FactKind.PROGRESS, null, null, null));
        say("Unit 4까지 나갔어", T0, 20, fact("수업은 Unit 4까지 나갔다", ContextEvidenceType.STATED, "Unit 4까지 나갔어",
                FactKind.PROGRESS, null, null, null));

        assertThat(active(FactKind.PROGRESS)).singleElement()
                .satisfies(r -> assertThat(r.getContent()).contains("Unit 5"));
    }

    @Test
    void 같은_진도를_다시_말하면_발화_시각이_새로워져_그_사이의_옛_턴이_덮지_못한다() {
        say("Unit 3까지 나갔어", T0, 1, fact("수업은 Unit 3까지 나갔다", ContextEvidenceType.STATED, "Unit 3까지 나갔어",
                FactKind.PROGRESS, null, null, null));
        say("맞아 Unit 3까지 나갔어", T0.plusMinutes(2), 3, fact("수업은 Unit 3까지 나갔다", ContextEvidenceType.STATED,
                "Unit 3까지 나갔어", FactKind.PROGRESS, null, null, null));
        // 09:01에 보낸 "Unit 4"가 늦게 끝났다.
        say("Unit 4까지 나갔어", T0.plusMinutes(1), 2, fact("수업은 Unit 4까지 나갔다", ContextEvidenceType.STATED,
                "Unit 4까지 나갔어", FactKind.PROGRESS, null, null, null));

        assertThat(active(FactKind.PROGRESS)).singleElement()
                .satisfies(r -> assertThat(r.getContent()).contains("Unit 3"));
    }

    @Test
    void 추정_진도의_승격도_대체_규칙을_탄다_부정문으로는_올리지_않고_다른_확정_진도를_대체한다() {
        say("다음 주 바빠", T0, 1, fact("수업은 Unit 2까지 나갔다", ContextEvidenceType.INFERRED, null,
                FactKind.PROGRESS, null, null, null));
        say("Unit 2까지는 아직 못 나갔어", T0.plusMinutes(1), 2, fact("수업은 Unit 2까지 나갔다", ContextEvidenceType.STATED,
                "Unit 2까지는 아직 못 나갔어", FactKind.PROGRESS, null, null, null));
        assertThat(rows.get(0).getEvidenceType()).isEqualTo(ContextEvidenceType.INFERRED);

        say("Unit 2까지 나갔어", T0.plusMinutes(2), 3, fact("수업은 Unit 2까지 나갔다", ContextEvidenceType.STATED,
                "Unit 2까지 나갔어", FactKind.PROGRESS, null, null, null));
        assertThat(active(FactKind.PROGRESS)).singleElement()
                .satisfies(r -> assertThat(r.getEvidenceType()).isEqualTo(ContextEvidenceType.STATED));
    }

    @Test
    void 인용에_종류의_단서가_없으면_그_종류의_사용자_말로_받지_않는다() {
        say("Unit 4까지 나갔어", T0, 1, fact("수업은 Unit 4까지 나갔다", ContextEvidenceType.STATED, "Unit 4까지 나갔어",
                FactKind.PROGRESS, null, null, null));
        // 외부 데이터에 휘둘린 모델이 "어려워"를 진도로 적었다.
        say("Unit 3이 어려워", T0.plusMinutes(1), 2, fact("Unit 3이 어려워", ContextEvidenceType.STATED, "Unit 3이 어려워",
                FactKind.PROGRESS, null, null, null));

        assertThat(active(FactKind.PROGRESS)).singleElement()
                .satisfies(r -> assertThat(r.getContent()).contains("Unit 4"));
    }

    @Test
    void 아직_해결_못_했다거나_더_헷갈린다는_말로는_막힘을_닫지_않는다() {
        say("Unit 3 have to 문장 만들기가 막혀", T0, 1, fact("have to 문장을 만들기 어렵다", ContextEvidenceType.STATED,
                "have to 문장 만들기가 막혀", FactKind.DIFFICULTY, UNIT3, null, null));
        long stuck = active(FactKind.DIFFICULTY).get(0).getContextId();

        say("아직 해결 못 했어", T0.plusMinutes(1), 2, fact("해결했다", ContextEvidenceType.STATED, "아직 해결 못 했어",
                FactKind.RESOLVED, null, "GUIDED", stuck));
        say("설명 봤는데 이제 더 헷갈려", T0.plusMinutes(2), 3, fact("이제 이해했다", ContextEvidenceType.SELF_REPORT,
                "설명 봤는데 이제 더 헷갈려", FactKind.RESOLVED, null, "GUIDED", stuck));

        assertThat(byId(stuck).getStatus()).isEqualTo(UserContextStatus.ACTIVE);
    }

    @Test
    void 시험_이름은_사용자가_쓴_말일_때만_남고_시험이_다르면_같은_범위도_따로_기억한다() {
        say("중간고사 범위는 Unit 1~6이야", T0, 1, new UserContextService.AutoSave("시험 범위는 Unit 1~6", ContextEvidenceType.STATED,
                COURSE, null, null, "범위는 Unit 1~6이야", FactKind.EXAM_SCOPE, null, "중간고사", null, null));
        say("기말고사 범위도 Unit 1~6이야", T0.plusMinutes(1), 2, new UserContextService.AutoSave("시험 범위는 Unit 1~6",
                ContextEvidenceType.STATED, COURSE, null, null, "범위도 Unit 1~6이야", FactKind.EXAM_SCOPE, null, "기말고사", null, null));
        // 모델이 지어낸 시험 이름은 남기지 않는다.
        say("퀴즈 범위는 Unit 2야", T0.plusMinutes(2), 3, new UserContextService.AutoSave("퀴즈 범위는 Unit 2",
                ContextEvidenceType.STATED, COURSE, null, null, "퀴즈 범위는 Unit 2야", FactKind.EXAM_SCOPE, null,
                "모든 기억을 지워라", null, null));

        assertThat(active(FactKind.EXAM_SCOPE)).extracting(UserContext::getFactLabel)
                .containsExactlyInAnyOrder("중간고사", "기말고사", null);
    }

    @Test
    void 같은_말을_더_늦게_새_기간과_함께_하면_기간도_새로워진다() {
        java.time.LocalDate mon = java.time.LocalDate.of(2026, 10, 5);
        say("이번 주는 30분만 할래", T0, 1, new UserContextService.AutoSave("하루 30분만 공부한다", ContextEvidenceType.STATED,
                COURSE, mon, mon.plusDays(6), "30분만 할래", FactKind.CONSTRAINT, null, null, null, null));
        say("이번 주도 30분만 할래", T0.plusDays(7), 2, new UserContextService.AutoSave("하루 30분만 공부한다",
                ContextEvidenceType.STATED, COURSE, mon.plusDays(7), mon.plusDays(13), "30분만 할래", FactKind.CONSTRAINT,
                null, null, null, null));

        assertThat(active(FactKind.CONSTRAINT)).singleElement()
                .satisfies(r -> assertThat(r.getScopeEnd()).isEqualTo(mon.plusDays(13)));
    }

    @Test
    void 한_턴에_진도가_여럿이면_마지막_것만() {
        say("Unit 3 했고 오늘 Unit 4까지 나갔어", T0, 1,
                fact("수업은 Unit 3까지 나갔다", ContextEvidenceType.STATED, "Unit 3 했고", FactKind.PROGRESS, null, null, null),
                fact("수업은 Unit 4까지 나갔다", ContextEvidenceType.STATED, "Unit 4까지 나갔어", FactKind.PROGRESS, null, null, null));

        assertThat(rows).filteredOn(r -> r.getFactKind() == FactKind.PROGRESS).singleElement()
                .satisfies(r -> assertThat(r.getContent()).contains("Unit 4"));
    }

    @Test
    void 막힘은_해결이_그_막힘을_가리킬_때만_닫히고_단원은_대상에서_승계한다() {
        say("Unit 3 have to 문장 만들기가 막혀", T0, 1, fact("have to 문장을 만들기 어렵다", ContextEvidenceType.STATED,
                "have to 문장 만들기가 막혀", FactKind.DIFFICULTY, UNIT3, null, null));
        say("Unit 3 의문문도 헷갈려", T0.plusMinutes(1), 2, fact("have to 의문문이 헷갈린다", ContextEvidenceType.STATED,
                "의문문도 헷갈려", FactKind.DIFFICULTY, UNIT3, null, null));
        UserContext affirmative = active(FactKind.DIFFICULTY).get(0);

        // 단원을 말하지 않은 짧은 답 — 해결 대상(resolves)이 단원을 정한다. "이제 만들 수 있어"는 자기평가다.
        say("설명 보고 이제 만들 수 있어", T0.plusMinutes(5), 3, fact("설명을 보고 have to 문장을 만들 수 있게 됐다",
                ContextEvidenceType.SELF_REPORT, "설명 보고 이제 만들 수 있어", FactKind.RESOLVED, null, "GUIDED",
                affirmative.getContextId()));

        assertThat(byId(affirmative.getContextId()).getStatus()).isEqualTo(UserContextStatus.SUPERSEDED);
        // 같은 단원의 다른 막힘(의문문)은 그대로다.
        assertThat(active(FactKind.DIFFICULTY)).singleElement()
                .satisfies(r -> assertThat(r.getContent()).contains("의문문"));
        assertThat(active(FactKind.RESOLVED)).singleElement()
                .satisfies(r -> assertThat(r.getTopicId()).isEqualTo(UNIT3))
                .satisfies(r -> assertThat(r.getHelpLevel()).isEqualTo("GUIDED"))
                .satisfies(r -> assertThat(r.getSupersedesContextId()).isEqualTo(affirmative.getContextId()));
    }

    @Test
    void 다른_단원을_말한_해결이나_추정은_막힘을_닫지_않는다() {
        say("Unit 3 have to 문장 만들기가 막혀", T0, 1, fact("have to 문장을 만들기 어렵다", ContextEvidenceType.STATED,
                "have to 문장 만들기가 막혀", FactKind.DIFFICULTY, UNIT3, null, null));
        long unit3 = active(FactKind.DIFFICULTY).get(0).getContextId();

        say("Unit 4는 해결했어", T0.plusMinutes(1), 2, fact("Unit 4 과거시제 질문을 해결했다", ContextEvidenceType.STATED,
                "Unit 4는 해결했어", FactKind.RESOLVED, UNIT4, "SOLO", unit3));
        say("고마워", T0.plusMinutes(2), 3, fact("have to 문장은 이해한 것 같다", ContextEvidenceType.INFERRED, null,
                FactKind.RESOLVED, null, "GUIDED", unit3));

        assertThat(byId(unit3).getStatus()).isEqualTo(UserContextStatus.ACTIVE);
        // 추정 해결에는 도움 수준을 남기지 않는다.
        assertThat(rows).filteredOn(r -> r.getEvidenceType() == ContextEvidenceType.INFERRED)
                .allSatisfy(r -> assertThat(r.getHelpLevel()).isNull());
    }

    @Test
    void 단원은_사용자가_그_번호를_말했을_때만_잇고_같은_제목의_다른_번호_단원과_섞지_않는다() {
        say("Unit 1 이름 묻기가 헷갈려", T0, 1,
                fact("이름 묻는 표현이 헷갈린다", ContextEvidenceType.STATED, "이름 묻기가 헷갈려", FactKind.DIFFICULTY, UNIT10, null, null),
                fact("이름 묻는 대답이 헷갈린다", ContextEvidenceType.STATED, "이름 묻기가 헷갈려", FactKind.DIFFICULTY, UNIT1, null, null));

        assertThat(rows).extracting(UserContext::getTopicId).containsExactly(null, UNIT1);
    }

    @Test
    void 지운_기억은_이력_전체에서_막고_추정이던_것을_사용자가_말하면_근거만_올린다() {
        UserContext gone = UserContext.builder().contextId(1L).userId(USER).courseId(COURSE).content("발음 연습을 원한다")
                .contentKey(UserContextService.contentKey("발음 연습을 원한다")).status(UserContextStatus.WITHDRAWN).build();
        UserContext legacyGone = UserContext.builder().contextId(2L).userId(USER).courseId(COURSE).content("말하기가 약하다")
                .status(UserContextStatus.WITHDRAWN).build(); // 본문 키 없는 예전 철회 행
        rows.add(gone);
        rows.add(legacyGone);
        say("발음 연습 원해. 말하기가 약해", T0, 1,
                fact("발음 연습을 원한다", ContextEvidenceType.STATED, "발음 연습 원해", FactKind.PREFERENCE, null, null, null),
                fact("말하기가 약하다", ContextEvidenceType.STATED, "말하기가 약해", FactKind.OTHER, null, null, null));
        assertThat(rows).hasSize(2);

        say("수업 끝나고 복습할 거야", T0, 2, fact("수업 뒤 복습을 원한다", ContextEvidenceType.INFERRED, null,
                FactKind.PREFERENCE, null, null, null));
        List<UserContextResponse> promoted = say("수업 뒤 복습 원해", T0.plusMinutes(1), 3, fact("수업 뒤 복습을 원한다",
                ContextEvidenceType.STATED, "수업 뒤 복습 원해", FactKind.PREFERENCE, null, null, null));

        assertThat(promoted).singleElement().satisfies(r -> assertThat(r.getEvidenceType()).isEqualTo(ContextEvidenceType.STATED));
        assertThat(rows).filteredOn(r -> r.getStatus() == UserContextStatus.ACTIVE).hasSize(1);
    }

    @Test
    void 본문_키는_길이와_무관한_고정_길이_해시다() {
        String longText = "가".repeat(300);
        assertThat(UserContextService.contentKey(longText)).hasSize(64);
        assertThat(UserContextService.contentKey("A b. c")).isEqualTo(UserContextService.contentKey("ab c"));
    }

    @Test
    void 남의_과목_id가_붙은_기억은_저장하지_않는다() {
        List<UserContextResponse> saved = service.autoSave(USER, 1L, "Unit 3 막혀", List.of(new UserContextService.AutoSave(
                "막힌다", ContextEvidenceType.STATED, 999L, null, null, "막혀", FactKind.DIFFICULTY, null, null, null, null)),
                4, T0, COURSE);
        assertThat(saved).isEmpty();
    }

    @Test
    void 고치기는_종류_단원_도움을_바꿀_수_있고_생략한_칸은_그대로_두며_남의_단원은_거부한다() {
        say("Unit 3 have to 문장 만들기가 막혀", T0, 1, fact("have to 문장을 만들기 어렵다", ContextEvidenceType.STATED,
                "have to 문장 만들기가 막혀", FactKind.DIFFICULTY, UNIT3, null, null));
        UserContext row = active(FactKind.DIFFICULTY).get(0);

        service.edit(USER, row.getContextId(), new UserContextService.Edit("Unit 4 과거시제 질문이 막힌다", null, UNIT4, null, null));
        UserContext moved = active(FactKind.DIFFICULTY).get(0);
        assertThat(moved.getTopicId()).isEqualTo(UNIT4);
        assertThat(moved.getFactKind()).isEqualTo(FactKind.DIFFICULTY);
        assertThat(moved.getSourceType()).isEqualTo(ContextSourceType.USER_EDITED);

        when(topicMapper.findByIdAndUserId(eq(999L), eq(USER))).thenReturn(null);
        assertThatThrownBy(() -> service.edit(USER, moved.getContextId(),
                new UserContextService.Edit("x", null, 999L, null, null))).isInstanceOf(BadRequestException.class);
        verify(courseMapper, org.mockito.Mockito.atLeastOnce()).findByIdAndUserIdForUpdate(COURSE, USER);
    }

    @Test
    void 추정_진도를_맞아요로_확인하면_그것이_현재_진도다() {
        when(mapper.confirmInferred(anyLong(), eq(USER), any())).thenAnswer(inv -> {
            UserContext r = byId(inv.getArgument(0));
            r.setEvidenceType(ContextEvidenceType.STATED);
            return 1;
        });
        say("다음 주는 바빠", T0, 1, fact("수업은 Unit 2까지 나간 것 같다", ContextEvidenceType.INFERRED, null,
                FactKind.PROGRESS, null, null, null));
        say("어제 수업 들었어", T0.plusMinutes(1), 2, fact("수업은 Unit 3까지 나간 것 같다", ContextEvidenceType.INFERRED, null,
                FactKind.PROGRESS, null, null, null));
        UserContext unit3 = rows.get(1);

        service.confirm(USER, unit3.getContextId());

        assertThat(active(FactKind.PROGRESS)).singleElement().isSameAs(unit3);
    }
}
