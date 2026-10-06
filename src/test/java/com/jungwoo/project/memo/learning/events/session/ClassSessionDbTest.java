package com.jungwoo.project.memo.learning.events.session;

import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.learning.events.LearningEvent;
import com.jungwoo.project.memo.learning.events.LearningEventLog;
import com.jungwoo.project.memo.routine.RoutineService;
import com.jungwoo.project.memo.routine.domain.RoutineExceptionType;
import com.jungwoo.project.memo.routine.dto.RoutineExceptionSaveRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 수업 회차·수업 확인: 끝난 수업만 묻고 휴강은 묻지 않는다, 확인은 회차별 원천 집합이고 요청 전체가 한 트랜잭션, 재전송은 성공·다른 판은
 * 409, 기본값은 지난 확인의 다음 구간, 일정 예외의 날짜를 바꾸면 옛 회차의 휴강 표시가 내려간다. 합성 사용자·합성 자료.
 * 로컬 memo_test DB 필요. CI 제외.
 */
@SpringBootTest
class ClassSessionDbTest {

    private static final long USER = 999_000_991L;
    /** 2026-10-07(수) 12:00 기준. 수업은 매일 09:00~10:30. */
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 7);

    @Autowired
    private ClassSessionService service;
    @Autowired
    private RoutineService routineService;
    @Autowired
    private LearningEventLog eventLog;
    @Autowired
    private DataSource dataSource;

    private long courseId;
    private long routineId;
    private long materialId;
    private long s1;
    private long s2;
    private long s3;

    @BeforeEach
    void setUp() throws Exception {
        cleanUp();
        service.setClock(Clock.fixed(TODAY.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault()));
        exec("INSERT INTO users (user_id, email, password_hash, nickname, role, status) "
                + "VALUES (?, ?, '!no-login', '수업 확인 테스트', 'USER', 'ACTIVE')", USER, "cls-" + USER + "@memo-test.invalid");
        courseId = insert("INSERT INTO courses (user_id, title, status) VALUES (?, '가온', 'ACTIVE')", USER);
        routineId = insert("INSERT INTO routines (user_id, course_id, title, start_time, end_time, effective_from, is_deleted) "
                + "VALUES (?, ?, '가온 수업', '09:00:00', '10:30:00', '2026-09-01', 0)", USER, courseId);
        for (String day : List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")) {
            exec("INSERT INTO routine_weekdays (routine_id, day_of_week) VALUES (?, ?)", routineId, day);
        }
        materialId = insert("INSERT INTO course_materials (user_id, original_filename, stored_filename, storage_path, size_bytes, "
                + "extraction_status, status) VALUES (?, 'ch01.pdf', 'x.pdf', 'none/x.pdf', 1, 'SUCCESS', 'ACTIVE')", USER);
        exec("INSERT INTO material_links (user_id, material_id, course_id, material_type) VALUES (?, ?, ?, 'PROFESSOR_SLIDE')",
                USER, materialId, courseId);
        s1 = section(0, 1, 10);
        s2 = section(1, 11, 20);
        s3 = section(2, 21, 30);
    }

    private long section(int chunk, int from, int to) throws Exception {
        return insert("INSERT INTO material_sections (user_id, material_id, file_hash, analysis_version, chunk_index, unit_type, "
                + "unit_start, unit_end, printed_page_start, printed_page_end, display_title, roles_json, dedupe_key) "
                + "VALUES (?, ?, ?, 1, ?, 'PAGE', ?, ?, ?, ?, ?, '[]', ?)", USER, materialId, "f".repeat(64), chunk, from, to,
                from, to, "구간 " + chunk, "k" + chunk);
    }

    private static ClassSessionService.ConfirmItem covered(long routine, LocalDate date, int expected, long... sections) {
        List<ClassSessionService.SourceInput> sources = java.util.Arrays.stream(sections)
                .mapToObj(s -> new ClassSessionService.SourceInput("SECTION", "s:" + s, null, null)).toList();
        return new ClassSessionService.ConfirmItem(routine, date, expected, ClassSessionService.Action.COVERED, sources);
    }

    private List<LearningEvent> live(String verb) {
        return eventLog.liveEvents(USER, courseId).stream().filter(e -> e.getVerb().equals(verb)).toList();
    }

    @Test
    void 끝난_수업만_묻고_시간표에서_쉰_날은_묻지_않는다() {
        routineService.addException(USER, routineId, new RoutineExceptionSaveRequest(TODAY.minusDays(1),
                RoutineExceptionType.SKIP, null, null, null, null, null));

        List<LocalDate> dates = service.pending(USER, courseId, 3).sessions().stream()
                .map(ClassSessionService.PendingSession::sourceDate).toList();

        assertThat(dates).containsExactly(TODAY.minusDays(2), TODAY);   // 어제는 휴강, 오늘 수업은 이미 끝남
        assertThat(service.pending(USER, courseId, 3).options()).extracting(ClassSessionService.SectionOption::sectionId)
                .containsExactly(s1, s2, s3);
    }

    @Test
    void 확인은_회차별_원천_집합이고_기본값은_지난_확인의_다음_구간이다() {
        LocalDate d1 = TODAY.minusDays(2);
        service.confirm(USER, courseId, List.of(covered(routineId, d1, 0, s1)));

        assertThat(live("COVERED_IN_CLASS")).singleElement()
                .satisfies(e -> assertThat(e.getObjectKind()).isEqualTo("SESSION"))
                .satisfies(e -> assertThat(e.getPayload()).contains("s:" + s1));
        ClassSessionService.PendingView view = service.pending(USER, courseId, 3);
        assertThat(view.sessions()).extracting(ClassSessionService.PendingSession::sourceDate).doesNotContain(d1);
        assertThat(view.sessions().get(0).defaults()).extracting(ClassSessionService.SectionOption::sectionId)
                .containsExactly(s2);
    }

    @Test
    void 같은_내용의_재전송은_성공하고_다른_판의_변경은_요청_전체가_409다() {
        LocalDate d1 = TODAY.minusDays(2);
        LocalDate d2 = TODAY.minusDays(1);
        service.confirm(USER, courseId, List.of(covered(routineId, d1, 0, s1)));
        // 재전송(같은 내용, 옛 판)
        assertThat(service.confirm(USER, courseId, List.of(covered(routineId, d1, 0, s1))).get(0).revision()).isEqualTo(1);

        // 다른 기기가 먼저 바꾼 회차(옛 판으로 다른 내용) + 정상 회차 → 아무것도 저장되지 않는다
        assertThatThrownBy(() -> service.confirm(USER, courseId, List.of(covered(routineId, d1, 0, s2),
                covered(routineId, d2, 0, s2)))).isInstanceOf(ConflictException.class);
        assertThat(live("COVERED_IN_CLASS")).hasSize(1);

        // 지금 판으로는 바꿀 수 있다
        service.confirm(USER, courseId, List.of(covered(routineId, d1, 1, s1, s2)));
        assertThat(live("COVERED_IN_CLASS")).singleElement()
                .satisfies(e -> assertThat(e.getPayload()).contains("s:" + s1).contains("s:" + s2));
    }

    @Test
    void 이_과목_자료가_아닌_원천과_수업이_없는_날은_400이다() throws Exception {
        long otherMaterial = insert("INSERT INTO course_materials (user_id, original_filename, stored_filename, storage_path, "
                + "size_bytes, extraction_status, status) VALUES (?, 'x.pdf', 'y.pdf', 'none/y.pdf', 1, 'SUCCESS', 'ACTIVE')", USER);
        assertThatThrownBy(() -> service.confirm(USER, courseId, List.of(new ClassSessionService.ConfirmItem(routineId,
                TODAY.minusDays(1), 0, ClassSessionService.Action.COVERED,
                List.of(new ClassSessionService.SourceInput("MATERIAL", "m:" + otherMaterial, null, null))))))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.confirm(USER, courseId, List.of(covered(routineId, LocalDate.of(2026, 8, 1), 0, s1))))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void 일정_예외의_날짜를_바꾸면_옛_회차의_휴강_표시가_내려가고_지우기도_된다() {
        LocalDate d1 = TODAY.minusDays(2);
        service.confirm(USER, courseId, List.of(new ClassSessionService.ConfirmItem(routineId, d1, 0,
                ClassSessionService.Action.UNKNOWN_CONTENT, List.of())));
        var exception = routineService.addException(USER, routineId, new RoutineExceptionSaveRequest(d1,
                RoutineExceptionType.SKIP, null, null, null, null, null));
        assertThat(live("CANCELLED")).hasSize(1);

        routineService.updateException(USER, routineId, exception.routineExceptionId(), new RoutineExceptionSaveRequest(
                TODAY.minusDays(4), RoutineExceptionType.SKIP, null, null, null, null, null));
        assertThat(live("CANCELLED")).isEmpty();

        routineService.deleteException(USER, routineId, exception.routineExceptionId());
        assertThat(live("CANCELLED")).isEmpty();
    }

    @Test
    void 시간이_지나_같은_확인을_다시_보내도_성공이다() {
        LocalDate d1 = TODAY.minusDays(2);
        service.confirm(USER, courseId, List.of(covered(routineId, d1, 0, s1, s2)));
        service.setClock(Clock.fixed(TODAY.atTime(18, 0).atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault()));

        // 원천 순서가 달라도 같은 확인이다
        assertThat(service.confirm(USER, courseId, List.of(covered(routineId, d1, 0, s2, s1))).get(0).revision()).isEqualTo(1);
    }

    @Test
    void 보강은_옮긴_시각까지_남는다() {
        LocalDate d1 = TODAY.minusDays(2);
        service.confirm(USER, courseId, List.of(new ClassSessionService.ConfirmItem(routineId, d1, 0,
                ClassSessionService.Action.UNKNOWN_CONTENT, List.of())));
        routineService.addException(USER, routineId, new RoutineExceptionSaveRequest(d1, RoutineExceptionType.MOVED,
                TODAY.minusDays(1), java.time.LocalTime.of(14, 0), java.time.LocalTime.of(15, 30), null, null));

        assertThat(live("SESSION_MOVED")).singleElement()
                .satisfies(e -> assertThat(e.getPayload()).contains("T14:00").contains("T15:30"));
    }

    @Test
    void 잘못된_입력은_400이다() {
        assertThatThrownBy(() -> service.confirm(USER, courseId, List.of(new ClassSessionService.ConfirmItem(routineId,
                TODAY.minusDays(1), 0, ClassSessionService.Action.COVERED,
                List.of(new ClassSessionService.SourceInput("MATERIAL", "s:" + s1, null, null))))))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.confirm(USER, courseId, List.of(new ClassSessionService.ConfirmItem(routineId,
                TODAY.minusDays(1), 0, ClassSessionService.Action.CANCELLED,
                List.of(new ClassSessionService.SourceInput("SECTION", "s:" + s1, null, null))))))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.confirm(USER, courseId, java.util.Arrays.asList((ClassSessionService.ConfirmItem) null)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void 끈_과목은_묻지_않는다() {
        service.setPromptEnabled(USER, courseId, false);
        ClassSessionService.PendingView view = service.pending(USER, courseId, 7);
        assertThat(view.promptEnabled()).isFalse();
        assertThat(view.sessions()).isEmpty();
    }

    private long insert(String sql, Object... args) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void exec(String sql, Object... args) throws Exception {
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    @AfterEach
    void cleanUp() throws Exception {
        exec("DELETE x FROM routine_exceptions x JOIN routines r ON r.routine_id = x.routine_id WHERE r.user_id = ?", USER);
        exec("DELETE w FROM routine_weekdays w JOIN routines r ON r.routine_id = w.routine_id WHERE r.user_id = ?", USER);
        for (String table : List.of("learning_events", "learning_event_origins", "class_sessions", "routines",
                "material_sections", "material_links", "course_materials", "courses", "users")) {
            exec("DELETE FROM " + table + " WHERE user_id = ?", USER);
        }
    }
}
