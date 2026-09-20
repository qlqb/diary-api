package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.domain.AiProposal;
import com.jungwoo.project.memo.ai.domain.ContextEvidenceType;
import com.jungwoo.project.memo.ai.domain.ContextSourceType;
import com.jungwoo.project.memo.ai.domain.UserContext;
import com.jungwoo.project.memo.ai.domain.UserContextStatus;
import com.jungwoo.project.memo.ai.dto.UserContextResponse;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.course.CourseMapper;
import com.jungwoo.project.memo.course.domain.Course;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * handoff T08·T09 — 사용자 상황의 근거 유형·범위·정정·철회.
 * 직접 진술 / 자기평가 / AI 추정을 섞지 않고, 지운 기억은 되살아나지 않는다.
 */
class UserContextServiceTest {

    private static final long USER = 7L;

    private final UserContextMapper mapper = mock(UserContextMapper.class);
    private final CourseMapper courseMapper = mock(CourseMapper.class);
    private final AiProposalMapper proposalMapper = mock(AiProposalMapper.class);
    private final UserContextService service = new UserContextService(mapper, courseMapper, proposalMapper);

    private final List<UserContext> rows = new ArrayList<>();
    private final List<UserContext> withdrawn = new ArrayList<>();
    private long nextId = 100;

    @BeforeEach
    void setUp() {
        when(courseMapper.findByUserIdAndStatus(USER, "ACTIVE"))
                .thenReturn(List.of(Course.builder().courseId(1L).userId(USER).title("자료구조").build()));
        when(courseMapper.findByUserIdAndStatus(USER, "ARCHIVED")).thenReturn(List.of());
        when(mapper.findActiveAndStaleByUserId(eq(USER), any())).thenAnswer(inv -> new ArrayList<>(rows));
        when(mapper.findWithdrawnByUserId(eq(USER), anyInt())).thenAnswer(inv -> new ArrayList<>(withdrawn));
        doAnswer(inv -> {
            UserContext c = inv.getArgument(0);
            c.setContextId(nextId++);
            rows.add(c);
            return null;
        }).when(mapper).insert(any());
        when(mapper.findByIdAndUserId(any(), eq(USER))).thenAnswer(inv -> rows.stream()
                .filter(r -> r.getContextId().equals(inv.getArgument(0))).findFirst().orElse(null));
        when(proposalMapper.findOpenPlanDraftsByUserId(USER)).thenReturn(List.of());
    }

    private static UserContextService.AutoSave op(String text, ContextEvidenceType type, Long courseId, String quote) {
        return new UserContextService.AutoSave(text, type, courseId, null, null, quote);
    }

    @Test
    void 직접_진술과_자기평가는_사용자가_실제로_한_말을_인용했을_때만_인정하고_아니면_AI_추정으로_낮춘다() {
        String said = "실습은 따라 하긴 했는데 혼자는 못 해. 이번 주는 알바가 있어";

        List<UserContextResponse> saved = service.autoSave(USER, 55L, said, List.of(
                op("실습을 따라 하긴 했지만 혼자서는 시작하지 못한다", ContextEvidenceType.SELF_REPORT, 1L, "혼자는 못 해"),
                op("이번 주는 알바가 있다", ContextEvidenceType.STATED, null, "알바가 있어"),
                // AI가 앞에서 한 말을 사용자 상태로 적으려는 경우 — 인용이 사용자 발화에 없다.
                op("반복문 개념이 약하다", ContextEvidenceType.STATED, 1L, "반복문이 약해요"),
                // 관찰은 서버만 만든다.
                op("과제를 세 번 미뤘다", ContextEvidenceType.OBSERVED, 1L, null)), 4);

        assertThat(saved).extracting(UserContextResponse::getEvidenceType).containsExactly(
                ContextEvidenceType.SELF_REPORT, ContextEvidenceType.STATED, ContextEvidenceType.INFERRED,
                ContextEvidenceType.INFERRED);
        assertThat(saved.get(0).getCourseTitle()).isEqualTo("자료구조");
        assertThat(saved.get(0).getSourceMessageId()).isEqualTo(55L);
        assertThat(rows).allMatch(r -> r.getSourceType() == ContextSourceType.CONSULT_AUTO);
        // 추정은 확인 시각이 없다 — 확인 전이다.
        assertThat(rows.get(2).getConfirmedAt()).isNull();
    }

    @Test
    void 남의_프로젝트_id는_범위로_받지_않고_한_턴에_저장하는_개수에_상한이_있다() {
        List<UserContextResponse> saved = service.autoSave(USER, 1L, "a b c d e", List.of(
                op("하나", ContextEvidenceType.STATED, 999L, "a"), op("둘", ContextEvidenceType.STATED, null, "b"),
                op("셋", ContextEvidenceType.STATED, null, "c")), 2);

        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getCourseId()).isNull();
    }

    @Test
    void T09_사용자가_지운_내용과_이미_있는_내용은_다시_저장하지_않는다() {
        withdrawn.add(UserContext.builder().contextId(1L).userId(USER).content("파이썬은 처음이다")
                .status(UserContextStatus.WITHDRAWN).build());
        rows.add(UserContext.builder().contextId(2L).userId(USER).content("과제는 마감만 챙긴다")
                .status(UserContextStatus.ACTIVE).build());

        List<UserContextResponse> saved = service.autoSave(USER, 9L, "파이썬은 처음이야. 과제는 마감만 챙길게", List.of(
                op("파이썬은 처음이다.", ContextEvidenceType.STATED, null, "파이썬은 처음"),
                op("과제는 마감만 챙긴다", ContextEvidenceType.STATED, null, "과제는 마감만")), 4);

        assertThat(saved).isEmpty();
        verify(mapper, never()).insert(any());
    }

    @Test
    void 고치면_옛_행은_이력으로_남고_새_행은_사용자의_말이며_그_기억을_쓴_열린_초안을_알려_준다() {
        rows.add(UserContext.builder().contextId(5L).userId(USER).content("오류가 나면 막힌다")
                .status(UserContextStatus.ACTIVE).evidenceType(ContextEvidenceType.INFERRED).courseId(1L)
                .scopeStart(LocalDate.of(2026, 9, 19)).scopeEnd(LocalDate.of(2026, 9, 20)).build());
        when(mapper.updateStatusIfIn(eq(5L), eq(USER), anyList(), eq(UserContextStatus.SUPERSEDED))).thenReturn(1);
        AiProposal citing = AiProposal.builder().proposalId(41L).planProvenanceJson(
                "{\"providedSources\":[{\"refId\":\"s2\",\"sourceType\":\"USER_CONTEXT\",\"sourceId\":5,\"x\":1}]}").build();
        AiProposal other = AiProposal.builder().proposalId(42L).planProvenanceJson(
                "{\"providedSources\":[{\"refId\":\"s2\",\"sourceType\":\"USER_CONTEXT\",\"sourceId\":55}]}").build();
        when(proposalMapper.findOpenPlanDraftsByUserId(USER)).thenReturn(List.of(citing, other));

        UserContextService.Change change = service.edit(USER, 5L, "  첫 코드를 못 시작한다 ");

        ArgumentCaptor<UserContext> inserted = ArgumentCaptor.forClass(UserContext.class);
        verify(mapper).insert(inserted.capture());
        assertThat(inserted.getValue().getContent()).isEqualTo("첫 코드를 못 시작한다");
        assertThat(inserted.getValue().getEvidenceType()).isEqualTo(ContextEvidenceType.STATED);
        assertThat(inserted.getValue().getSourceType()).isEqualTo(ContextSourceType.USER_EDITED);
        assertThat(inserted.getValue().getSupersedesContextId()).isEqualTo(5L);
        // 적용 범위는 그대로 이어진다.
        assertThat(inserted.getValue().getCourseId()).isEqualTo(1L);
        assertThat(inserted.getValue().getScopeEnd()).isEqualTo(LocalDate.of(2026, 9, 20));
        // 55번을 근거로 든 초안은 5번과 무관하다(앞자리가 같아도).
        assertThat(change.staleDraftIds()).containsExactly(41L);
    }

    @Test
    void 이미_고쳤거나_지운_것을_다시_바꾸려_하면_충돌이고_남의_것은_없는_것이다() {
        rows.add(UserContext.builder().contextId(5L).userId(USER).content("x").status(UserContextStatus.ACTIVE).build());
        when(mapper.withdraw(eq(5L), eq(USER), any())).thenReturn(0);

        assertThatThrownBy(() -> service.withdraw(USER, 5L)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.withdraw(USER, 6L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void 점검_활동의_자기평가는_숙달이_아니라_자기평가로_저장되고_같은_대상의_예전_답은_대체된다() {
        UserContext old = UserContext.builder().contextId(3L).userId(USER).courseId(1L).topicId(11L)
                .content("스택 — 처음 본다고 답함").status(UserContextStatus.ACTIVE).build();
        when(mapper.findActiveSelfChecks(USER, 1L)).thenReturn(List.of(old));

        int saved = service.saveSelfChecks(USER, 1L, List.of(
                new UserContextService.SelfCheck("스택", 11L, null, "unsure", "push/pop은 알아"),
                new UserContextService.SelfCheck("큐", 12L, null, "MASTERED", null)));

        assertThat(saved).isEqualTo(1); // 모르는 값(MASTERED)은 받지 않는다.
        verify(mapper).updateStatusIfIn(eq(3L), eq(USER), anyList(), eq(UserContextStatus.SUPERSEDED));
        UserContext row = rows.get(0);
        assertThat(row.getEvidenceType()).isEqualTo(ContextEvidenceType.SELF_REPORT);
        assertThat(row.getSourceType()).isEqualTo(ContextSourceType.SELF_CHECK);
        assertThat(row.getSelfLevel()).isEqualTo("UNSURE");
        assertThat(row.getContent()).isEqualTo("스택 — 애매하다고 답함 (push/pop은 알아)");
        assertThatThrownBy(() -> service.saveSelfChecks(USER, 999L, List.of())).isInstanceOf(NotFoundException.class);
    }
}
