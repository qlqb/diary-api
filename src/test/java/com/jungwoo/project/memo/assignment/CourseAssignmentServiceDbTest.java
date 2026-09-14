package com.jungwoo.project.memo.assignment;

import com.jungwoo.project.memo.assignment.domain.AssignmentConfirmStatus;
import com.jungwoo.project.memo.assignment.domain.CourseAssignment;
import com.jungwoo.project.memo.assignment.domain.DueKind;
import com.jungwoo.project.memo.assignment.domain.DueSource;
import com.jungwoo.project.memo.assignment.dto.AssignmentRequests;
import com.jungwoo.project.memo.assignment.dto.AssignmentResponse;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.material.domain.MaterialSection;
import com.jungwoo.project.memo.material.domain.TextUnitType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 과제의 원본이 하나라는 것, 재분석이 사용자 값을 덮지 않는다는 것, 추정 마감이 마감이 되지 않는다는 것을
 * 실제 DB에서 본다(표 13·14·15·17). 로컬 memo DB 필요. CI 제외.
 */
@SpringBootTest
class CourseAssignmentServiceDbTest {

    private static final long USER = 999_000_302L;
    private static final long MATERIAL = 999_300_101L;

    @Autowired
    private CourseAssignmentService service;
    @Autowired
    private CourseAssignmentMapper mapper;
    @Autowired
    private DataSource dataSource;

    @BeforeEach
    @AfterEach
    void cleanUp() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM course_assignments WHERE user_id = ?")) {
            ps.setLong(1, USER);
            ps.executeUpdate();
        }
    }

    private MaterialSection section(long sectionId, String dedupe, boolean cue, String datesJson) {
        return MaterialSection.builder().sectionId(sectionId).userId(USER).materialId(MATERIAL).fileHash("h")
                .unitType(TextUnitType.PDF_PAGE).unitStart(3).unitEnd(3).displayTitle("연결 리스트 실습 과제")
                .sectionLabel("실습 3").excerpt("실습 결과 화면과 코드를 제출하세요").assignmentCue(cue)
                .assignmentQuote(cue ? "실습 결과 화면과 코드를 제출하세요" : null).dateCandidatesJson(datesJson)
                .dedupeKey(dedupe).rolesJson("[\"ASSIGNMENT\"]").status("ACTIVE").build();
    }

    @Test
    void 같은_구간에서_다시_나와도_과제는_하나이고_사용자_답은_유지된다() {
        MaterialSection s = section(1, "s3-3:aaa", true, null);
        assertThat(service.upsertCandidateFromSection(s, null, null)).isTrue();
        assertThat(service.upsertCandidateFromSection(s, null, null)).isFalse();
        List<CourseAssignment> rows = mapper.findByMaterialId(MATERIAL, USER);
        assertThat(rows).hasSize(1);
        CourseAssignment candidate = rows.get(0);
        assertThat(candidate.getConfirmStatus()).isEqualTo(AssignmentConfirmStatus.CANDIDATE);
        assertThat(candidate.getDueKind()).isEqualTo(DueKind.UNKNOWN);

        AssignmentRequests.Answer yes = new AssignmentRequests.Answer();
        yes.setAnswer(AssignmentConfirmStatus.CONFIRMED);
        yes.setVersion(candidate.getVersion());
        AssignmentResponse confirmed = service.answer(USER, candidate.getAssignmentId(), yes);
        assertThat(confirmed.getConfirmStatus()).isEqualTo(AssignmentConfirmStatus.CONFIRMED);

        // 재분석이 같은 구간을 다시 냈다 — 확정 상태는 그대로다.
        service.upsertCandidateFromSection(s, null, null);
        assertThat(mapper.findByIdAndUserId(candidate.getAssignmentId(), USER).getConfirmStatus())
                .isEqualTo(AssignmentConfirmStatus.CONFIRMED);
        assertThat(mapper.findByMaterialId(MATERIAL, USER)).hasSize(1);
    }

    @Test
    void 연도가_없는_날짜와_상대_표현은_마감이_아니라_추정_후보로만_남는다() {
        String dates = "[{\"text\":\"9월 18일까지 제출\",\"isoDate\":null,\"monthDay\":\"09-18\",\"kind\":\"DUE\",\"relative\":false},"
                + "{\"text\":\"다음 수업까지\",\"isoDate\":null,\"monthDay\":null,\"kind\":\"DUE\",\"relative\":true}]";
        service.upsertCandidateFromSection(section(2, "s3-3:bbb", true, dates), null, null);
        CourseAssignment a = mapper.findByMaterialId(MATERIAL, USER).get(0);

        assertThat(a.getDueKind()).isEqualTo(DueKind.UNKNOWN);
        assertThat(a.getDueDate()).isNull();
        assertThat(a.getDueSource()).isNull();
        assertThat(a.getDueQuote()).isEqualTo("9월 18일까지 제출");
        assertThat(a.getDueEstimateJson()).contains("09-18").contains("다음 수업까지");
        AssignmentResponse response = service.get(USER, a.getAssignmentId());
        assertThat(response.getDueEstimates()).hasSize(2);
    }

    @Test
    void 연도까지_명시된_제출_날짜만_원문_출처_마감으로_미리_채운다() {
        String dates = "[{\"text\":\"2026년 9월 18일까지 제출\",\"isoDate\":\"2026-09-18\",\"monthDay\":\"09-18\",\"kind\":\"DUE\",\"relative\":false}]";
        service.upsertCandidateFromSection(section(3, "s3-3:ccc", true, dates), null, null);
        CourseAssignment a = mapper.findByMaterialId(MATERIAL, USER).get(0);
        assertThat(a.getDueKind()).isEqualTo(DueKind.DATE);
        assertThat(a.getDueDate()).isEqualTo(LocalDate.of(2026, 9, 18));
        assertThat(a.getDueSource()).isEqualTo(DueSource.SOURCE);
    }

    @Test
    void 사용자가_고친_마감은_재분석이_덮지_않고_완료_체크는_버전으로_지킨다() {
        String dates = "[{\"text\":\"2026년 9월 18일까지 제출\",\"isoDate\":\"2026-09-18\",\"monthDay\":\"09-18\",\"kind\":\"DUE\",\"relative\":false}]";
        MaterialSection s = section(4, "s3-3:ddd", true, dates);
        service.upsertCandidateFromSection(s, null, null);
        CourseAssignment a = mapper.findByMaterialId(MATERIAL, USER).get(0);

        AssignmentRequests.Due due = new AssignmentRequests.Due();
        due.setDueKind(DueKind.DATE);
        due.setDueDate(LocalDate.of(2026, 9, 25));
        due.setVersion(a.getVersion());
        AssignmentResponse edited = service.setDue(USER, a.getAssignmentId(), due);
        assertThat(edited.getDueDate()).isEqualTo(LocalDate.of(2026, 9, 25));
        assertThat(edited.getDueSource()).isEqualTo(DueSource.USER);
        assertThat(edited.isDueEdited()).isTrue();

        // 재분석: 원문 날짜가 다시 와도 사용자 값이 남는다.
        service.upsertCandidateFromSection(s, null, null);
        CourseAssignment after = mapper.findByIdAndUserId(a.getAssignmentId(), USER);
        assertThat(after.getDueDate()).isEqualTo(LocalDate.of(2026, 9, 25));

        // 오래된 version으로 완료 체크 → 409. 최신 version으로는 성공, 해제도 성공.
        AssignmentRequests.Complete stale = new AssignmentRequests.Complete();
        stale.setCompleted(true);
        stale.setVersion(a.getVersion());
        assertThatThrownBy(() -> service.setCompleted(USER, a.getAssignmentId(), stale))
                .isInstanceOf(ConflictException.class);

        AssignmentRequests.Complete fresh = new AssignmentRequests.Complete();
        fresh.setCompleted(true);
        fresh.setVersion(after.getVersion());
        AssignmentResponse done = service.setCompleted(USER, a.getAssignmentId(), fresh);
        assertThat(done.isCompleted()).isTrue();
        assertThat(done.getCompletedAt()).isNotNull();

        AssignmentRequests.Complete undo = new AssignmentRequests.Complete();
        undo.setCompleted(false);
        undo.setVersion(done.getVersion());
        assertThat(service.setCompleted(USER, a.getAssignmentId(), undo).isCompleted()).isFalse();
    }

    @Test
    void 마감_없음과_마감_미확인은_다른_값이고_확정_미완료_목록에_과거_마감도_남는다() {
        service.upsertCandidateFromSection(section(5, "s3-3:eee", true, null), null, null);
        CourseAssignment a = mapper.findByMaterialId(MATERIAL, USER).get(0);
        AssignmentRequests.Answer yes = new AssignmentRequests.Answer();
        yes.setAnswer(AssignmentConfirmStatus.CONFIRMED);
        yes.setVersion(a.getVersion());
        long v = service.answer(USER, a.getAssignmentId(), yes).getVersion();

        AssignmentRequests.Due none = new AssignmentRequests.Due();
        none.setDueKind(DueKind.NONE);
        none.setVersion(v);
        AssignmentResponse noDue = service.setDue(USER, a.getAssignmentId(), none);
        assertThat(noDue.getDueKind()).isEqualTo(DueKind.NONE);

        AssignmentRequests.Due past = new AssignmentRequests.Due();
        past.setDueKind(DueKind.DATE);
        past.setDueDate(LocalDate.of(2020, 1, 1));
        past.setVersion(noDue.getVersion());
        AssignmentResponse overdue = service.setDue(USER, a.getAssignmentId(), past);
        assertThat(overdue.isOverdue()).isTrue();
        assertThat(overdue.isCompleted()).isFalse();
        assertThat(service.listOpen(USER)).extracting(AssignmentResponse::getAssignmentId).contains(a.getAssignmentId());
    }

    @Test
    void 제목만_같은_다른_자료의_과제는_별개_행이고_사용자가_같다고_해야_DUPLICATE가_된다() {
        service.upsertCandidateFromSection(section(6, "s3-3:fff", true, null), null, null);
        MaterialSection other = section(7, "s9-9:ggg", true, null);
        other.setMaterialId(MATERIAL + 1);
        service.upsertCandidateFromSection(other, null, null);
        List<CourseAssignment> mine = mapper.findByMaterialId(MATERIAL, USER);
        List<CourseAssignment> theirs = mapper.findByMaterialId(MATERIAL + 1, USER);
        assertThat(mine).hasSize(1);
        assertThat(theirs).hasSize(1);
        assertThat(theirs.get(0).getTitle()).isEqualTo(mine.get(0).getTitle());

        AssignmentRequests.Answer same = new AssignmentRequests.Answer();
        same.setAnswer(AssignmentConfirmStatus.DUPLICATE);
        same.setDuplicateOfAssignmentId(mine.get(0).getAssignmentId());
        same.setVersion(theirs.get(0).getVersion());
        AssignmentResponse merged = service.answer(USER, theirs.get(0).getAssignmentId(), same);
        assertThat(merged.getConfirmStatus()).isEqualTo(AssignmentConfirmStatus.DUPLICATE);
        assertThat(merged.getDuplicateOfTitle()).isEqualTo(mine.get(0).getTitle());
    }
}
