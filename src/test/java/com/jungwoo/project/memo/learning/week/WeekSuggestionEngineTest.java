package com.jungwoo.project.memo.learning.week;

import com.jungwoo.project.memo.learning.week.WeekSuggestionEngine.Confidence;
import com.jungwoo.project.memo.learning.week.WeekSuggestionEngine.Confirmed;
import com.jungwoo.project.memo.learning.week.WeekSuggestionEngine.MaterialInput;
import com.jungwoo.project.memo.learning.week.WeekSuggestionEngine.Option;
import com.jungwoo.project.memo.learning.week.WeekSuggestionEngine.Placement;
import com.jungwoo.project.memo.learning.week.WeekSuggestionEngine.SectionInput;
import com.jungwoo.project.memo.learning.week.WeekSuggestionEngine.SignalKind;
import com.jungwoo.project.memo.learning.week.WeekSuggestionEngine.Suggestion;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 자료 주차 추천.
 *
 * <p>두 과목 모양은 로컬 DB에서 실제로 문제가 난 자료를 옮긴 것이다(파일명·구간 제목·역할·발췌). 사용자 식별자는 싣지 않는다.
 * <ul>
 *   <li>네트워크프로그래밍: 실제 1~3주차 자료가 전부 "주차 없음"이었고, 강의계획서의 "1~3주차 …" 구간이 3주차 칸을 차지했다.</li>
 *   <li>센서활용프로그래밍: 강의계획서의 주차별 구간이 1~15주차를 채웠고 "9~12주차"는 12주차 하나로 읽혔다.</li>
 * </ul>
 */
class WeekSuggestionEngineTest {

    private static final List<String> SCHEDULE = List.of("SCHEDULE", "CONCEPT", "EXERCISE");

    private static SectionInput section(long id, String title, String label, List<String> roles, String excerpt) {
        return new SectionInput(id, title, label, roles, excerpt);
    }

    private static SectionInput concept(long id, String title) {
        return section(id, title, null, List.of("CONCEPT"), null);
    }

    private static MaterialInput material(long id, String filename, String documentTitle, String weekLabel,
                                          SectionInput... sections) {
        return new MaterialInput(id, filename, documentTitle, weekLabel, List.of(sections));
    }

    // ===== 네트워크프로그래밍 =====

    static final long INTRO = 1;
    static final long LINUX = 2;
    static final long AWS = 3;
    static final long SYLLABUS = 4;
    static final long SCRIPT = 5;
    static final long SCRIPT_2 = 6;

    static MaterialInput networkIntro() {
        return material(INTRO, "01.수업소개_네트워크프로그래밍.pdf", null, null,
                section(556, "네트워크프로그래밍 강의 정보", null, List.of("ADMIN"), "네트워크프로그래밍 김규완"),
                section(557, "교수 소개", null, List.of("ADMIN"), null),
                concept(559, "네트워크와 요청·응답"),
                section(560, "평가 항목과 시험·과제", null, List.of("ADMIN", "ASSIGNMENT"), null),
                section(561, "강의 계획과 실습 언어", null, List.of("SCHEDULE"),
                        "강의 계획 ( 실습 언어 : C ) 1 오리엔테이션, 수업 소개 2 1장 – 네트워크와 소켓 프로그래밍"),
                section(563, "다음 강의 내용", null, List.of("SCHEDULE"),
                        "다음 강의 10 - 환경설정 - 네트워크와 소켓 프로그래밍"));
    }

    static MaterialInput networkLinux() {
        return material(LINUX, "2.리눅스_개요와_실습환경_구축_WSL2추가_v2.pdf", null, null,
                section(538, "리눅스 개요와 실습환경 구축", null, List.of("REFERENCE", "SCHEDULE"), null),
                concept(539, "운영체제 개요와 발전 역사"),
                concept(543, "가상머신과 WSL2 실습환경"),
                section(546, "다음 시간 안내", null, List.of("SCHEDULE"), "다음 시간 • 리눅스 사용 기초 및 AWS"));
    }

    /**
     * 본문 어디에도 "3주차"가 없다. 본문의 주차 표기는 전부 다른 주를 가리킨다("2주차에 배포한", "7주차부터").
     * 분석(weekLabel)도 그래서 null이었다. 3주차는 파일 속성 제목에만 있다.
     */
    static MaterialInput networkAws(String documentTitle) {
        return material(AWS, "3.AWS_구성하기_SSH실습.pdf", documentTitle, null,
                section(523, "AWS 구성하기 수업 개요", null, List.of("REFERENCE", "SCHEDULE"), null),
                concept(524, "서버와 클라이언트 및 OSI 7계층"),
                section(532, "수업 서버 SSH 접속과 오류 해결", null, List.of("EXAMPLE", "CONCEPT"),
                        "- 2주차에 배포한 ~/w02 폴더(log.txt·hosts)"),
                section(547, "SSH 서버 파일·권한·프로세스 실습", null, List.of("CONCEPT", "EXAMPLE"),
                        "7주차부터 여러분 서버가 열 포트"),
                section(551, "EC2 내 MySQL 설치와 조회", null, List.of("CONCEPT", "EXAMPLE"), null),
                section(552, "RDS 생성과 파라미터 그룹 설정", null, List.of("CONCEPT", "EXAMPLE"), null),
                section(553, "EC2에서 RDS 연결", null, List.of("EXAMPLE", "EXERCISE"), null));
    }

    static MaterialInput networkSyllabus() {
        return material(SYLLABUS, "네트워크프로그래밍.pdf", null, null,
                section(428, "네트워크프로그래밍 과목 개요", null, List.of("CONCEPT", "REFERENCE"), null),
                section(429, "수업 도구와 평가 방법", null, List.of("ADMIN", "REFERENCE"), null),
                section(430, "오리엔테이션과 기초 소켓 프로그래밍", "1~3주차", SCHEDULE, null),
                section(431, "TCP 서버와 멀티스레드 실습", "4~7주차", SCHEDULE, null),
                section(432, "중간고사", "8주차", List.of("SCHEDULE"), null),
                section(433, "UDP와 GUI 소켓 응용", "9~11주차", SCHEDULE, null),
                section(434, "입출력 모델·통신 프로젝트·기말고사", "12~15주차", SCHEDULE, null));
    }

    static final String AWS_TITLE = "AWS 구성하기 + SSH 실습 (네트워크프로그래밍 3주차)";

    static List<MaterialInput> networkCourse() {
        return List.of(networkIntro(), networkLinux(), networkAws(AWS_TITLE), networkSyllabus());
    }

    @Nested
    class 네트워크프로그래밍 {

        @Test
        void 실제_진행대로_1_2_3주차와_전체_참고를_추천한다() {
            Map<Long, Suggestion> s = WeekSuggestionEngine.suggest("네트워크프로그래밍", networkCourse(), Map.of());

            assertWeek(s.get(AWS), 3, Confidence.HIGH);
            assertWeek(s.get(LINUX), 2, Confidence.MEDIUM);
            assertWeek(s.get(INTRO), 1, Confidence.MEDIUM);
            assertThat(s.get(SYLLABUS).placement()).isEqualTo(Placement.COURSE_WIDE);
            assertThat(s.get(SYLLABUS).confidence()).isEqualTo(Confidence.HIGH);
        }

        @Test
        void AWS_자료는_파일_속성_제목이_주차를_말한다() {
            Suggestion aws = WeekSuggestionEngine.suggest("네트워크프로그래밍", networkCourse(), Map.of()).get(AWS);

            assertThat(kinds(aws)).contains(SignalKind.DOCUMENT_TITLE_WEEK, SignalKind.FILENAME_NUMBER);
            assertThat(aws.signals()).filteredOn(sig -> sig.kind() == SignalKind.DOCUMENT_TITLE_WEEK)
                    .singleElement().satisfies(sig -> assertThat(sig.detail()).contains("네트워크프로그래밍 3주차"));
            // 본문의 "2주차에 배포한", "7주차부터"는 이 자료의 주차가 아니다 — 신호로 읽지 않는다.
            assertThat(aws.signals()).noneMatch(sig -> sig.week() != null && (sig.week() == 2 || sig.week() == 7));
        }

        @Test
        void 리눅스_자료는_다음_시간_예고와_순번이_같은_주를_가리켜_MEDIUM이다() {
            Suggestion linux = WeekSuggestionEngine.suggest("네트워크프로그래밍", networkCourse(), Map.of()).get(LINUX);

            assertThat(kinds(linux)).contains(SignalKind.NEXT_LECTURE, SignalKind.FILENAME_NUMBER);
            assertThat(linux.signals()).filteredOn(sig -> sig.kind() == SignalKind.NEXT_LECTURE)
                    .anySatisfy(sig -> assertThat(sig.detail()).contains("3.AWS").contains("AWS"));
        }

        @Test
        void 강의계획서의_예정_주차는_실제_주차_신호가_아니다() {
            Suggestion syllabus = WeekSuggestionEngine.suggest("네트워크프로그래밍", networkCourse(), Map.of())
                    .get(SYLLABUS);

            assertThat(syllabus.options()).containsExactly(new Option(Placement.COURSE_WIDE, null));
            assertThat(syllabus.signals()).noneMatch(sig -> sig.placement() == Placement.WEEK);
            assertThat(syllabus.signals()).filteredOn(sig -> sig.kind() == SignalKind.COURSE_PLAN).singleElement()
                    .satisfies(sig -> assertThat(sig.detail()).contains("1~15주차"));
        }

        @Test
        void 파일_속성_제목이_없어도_앞_자료의_예고를_따라_이어서_추천한다() {
            List<MaterialInput> course = List.of(networkIntro(), networkLinux(), networkAws(null), networkSyllabus());

            Map<Long, Suggestion> s = WeekSuggestionEngine.suggest("네트워크프로그래밍", course, Map.of());

            // 수업소개(순번 1 + 첫 주 이름) → 리눅스(순번 2 + "환경설정" 예고) → AWS(순번 3 + "AWS" 예고).
            // 단계마다 서로 다른 약한 신호 둘이 같은 주를 가리킨다.
            assertWeek(s.get(INTRO), 1, Confidence.MEDIUM);
            assertWeek(s.get(LINUX), 2, Confidence.MEDIUM);
            assertWeek(s.get(AWS), 3, Confidence.MEDIUM);
            assertThat(kinds(s.get(AWS))).containsExactlyInAnyOrder(SignalKind.FILENAME_NUMBER, SignalKind.NEXT_LECTURE);
            assertThat(s.get(SYLLABUS).placement()).isEqualTo(Placement.COURSE_WIDE);
        }

        @Test
        void 닻이_없으면_약한_신호끼리_서로를_근거로_삼지_않는다() {
            // 수업소개가 없으면 리눅스·AWS는 순번 하나씩뿐이다. 서로의 예고를 근거로 MEDIUM이 되지 않는다.
            List<MaterialInput> course = List.of(networkLinux(), networkAws(null), networkSyllabus());

            Map<Long, Suggestion> s = WeekSuggestionEngine.suggest("네트워크프로그래밍", course, Map.of());

            assertWeek(s.get(LINUX), 2, Confidence.LOW);
            assertWeek(s.get(AWS), 3, Confidence.LOW);
        }

        @Test
        void 주차_단서가_없는_실습_스크립트는_구간_이름이_맞는_강의_자료의_주차를_약하게_추천한다() {
            List<MaterialInput> course = new ArrayList<>(networkCourse());
            course.add(material(SCRIPT, "MySQL실습스크립트.sh", null, null));
            course.add(material(SCRIPT_2, "MySQL실습스크립트_2.sh", null, null));

            Map<Long, Suggestion> s = WeekSuggestionEngine.suggest("네트워크프로그래밍", course, Map.of());

            for (long id : new long[]{SCRIPT, SCRIPT_2}) {
                assertWeek(s.get(id), 3, Confidence.LOW);
                assertThat(s.get(id).bulkApplicable()).as("관계 하나로는 일괄 적용하지 않는다").isFalse();
                assertThat(s.get(id).signals()).singleElement().satisfies(sig -> {
                    assertThat(sig.kind()).isEqualTo(SignalKind.PRACTICE_OF);
                    assertThat(sig.detail()).contains("EC2 내 MySQL 설치와 조회");
                });
            }
        }

        @Test
        void 확정된_주차가_관계의_닻이_된다() {
            // AWS 자료에 아무 단서가 없어도 사용자가 3주차로 확인했다면, 스크립트는 그 주차를 추천받는다.
            List<MaterialInput> course = List.of(material(AWS, "AWS.pdf", null, null,
                            concept(551, "EC2 내 MySQL 설치와 조회")),
                    material(SCRIPT, "MySQL실습스크립트.sh", null, null));

            Map<Long, Suggestion> s = WeekSuggestionEngine.suggest("네트워크프로그래밍", course,
                    Map.of(AWS, new Confirmed(Placement.WEEK, Set.of(3))));

            assertWeek(s.get(SCRIPT), 3, Confidence.LOW);
            assertThat(s).doesNotContainKey(AWS);
        }

        @Test
        void 확정된_자료에는_관계_신호를_얹지_않는다() {
            Map<Long, Suggestion> s = WeekSuggestionEngine.suggest("네트워크프로그래밍", networkCourse(),
                    Map.of(LINUX, new Confirmed(Placement.WEEK, Set.of(5))));

            assertThat(s.get(LINUX).signals()).noneMatch(sig -> sig.kind() == SignalKind.NEXT_LECTURE);
        }
    }

    // ===== 센서활용프로그래밍 =====

    static List<MaterialInput> sensorCourse() {
        return List.of(
                material(11, "0과 Orientation_1.pdf", "슬라이드 1", null,
                        section(1, "Orientation", null, List.of("OTHER"), null),
                        section(2, "교수소개", null, List.of("ADMIN"), null)),
                material(12, "1주차 .pdf", "슬라이드 1", null, concept(3, "아두이노 소개")),
                material(13, "2주차 .pdf", "슬라이드 1", "2주차", concept(4, "아두이노 구조")),
                material(14, "2주차  (1).pdf", "슬라이드 1", null, concept(5, "개발환경")),
                material(15, "2주차_1 .pdf", "슬라이드 1", null, concept(6, "디지털 출력")),
                material(16, "3주_2.pdf", "슬라이드 1", null, concept(7, "LED 회로")),
                material(17, "3주__1.pdf", "슬라이드 1", null, concept(8, "입출력")),
                material(18, "센서활용프로그래밍.pdf", null, null,
                        section(20, "센서활용프로그래밍 과목 개요와 학습목표", null, List.of("CONCEPT", "REFERENCE"), null),
                        section(21, "오리엔테이션과 아두이노 기본기", "1주차", SCHEDULE, null),
                        section(22, "아두이노 구조와 개발환경", "2주차", SCHEDULE, null),
                        section(23, "입출력과 LED 회로", "3주차", SCHEDULE, null),
                        section(24, "기본 센서와 아날로그 입력", "6주차", SCHEDULE, null),
                        section(25, "부저와 아두이노 통신", "7주차", SCHEDULE, null),
                        section(26, "다양한 센서와 7세그먼트·LCD", "9~12주차", SCHEDULE, null),
                        section(27, "블루투스 통신과 기말고사", "14~15주차", List.of("SCHEDULE", "CONCEPT"), null)));
    }

    @Nested
    class 센서활용프로그래밍 {

        @Test
        void 파일명의_주차가_실제_주차를_추천하고_강의계획서는_전체_참고다() {
            Map<Long, Suggestion> s = WeekSuggestionEngine.suggest("센서활용프로그래밍", sensorCourse(), Map.of());

            assertWeek(s.get(12L), 1, Confidence.HIGH);
            for (long id : new long[]{13, 14, 15}) {
                assertWeek(s.get(id), 2, Confidence.HIGH);
            }
            assertWeek(s.get(16L), 3, Confidence.HIGH);
            assertWeek(s.get(17L), 3, Confidence.HIGH);
            assertThat(s.get(18L).placement()).isEqualTo(Placement.COURSE_WIDE);
        }

        @Test
        void 예정_범위_9에서_12주차는_12주차_하나가_아니라_범위로_읽는다() {
            MaterialInput syllabus = sensorCourse().get(7);

            assertThat(WeekSuggestionEngine.plannedWeeks(syllabus)).contains(9, 10, 11, 12, 14, 15);
        }

        @Test
        void 편집기가_남긴_속성_제목은_신호가_아니다() {
            Suggestion one = WeekSuggestionEngine.suggest("센서활용프로그래밍", sensorCourse(), Map.of()).get(12L);

            assertThat(kinds(one)).doesNotContain(SignalKind.DOCUMENT_TITLE_WEEK);
        }

        @Test
        void 오리엔테이션_자료는_첫_주를_약하게만_추천한다() {
            Suggestion orientation = WeekSuggestionEngine.suggest("센서활용프로그래밍", sensorCourse(), Map.of()).get(11L);

            assertWeek(orientation, 1, Confidence.LOW);
            assertThat(orientation.bulkApplicable()).isFalse();
        }
    }

    // ===== 규칙 =====

    @Test
    void 강한_신호가_서로_다르면_고르지_않고_충돌로_둔다() {
        MaterialInput m = material(1, "2주차_AWS.pdf", "AWS 실습 (3주차)", null);

        Suggestion s = WeekSuggestionEngine.suggest("과목", List.of(m), Map.of()).get(1L);

        assertThat(s.confidence()).isEqualTo(Confidence.CONFLICT);
        assertThat(s.placement()).isNull();
        assertThat(s.options()).containsExactly(new Option(Placement.WEEK, 2), new Option(Placement.WEEK, 3));
        assertThat(s.bulkApplicable()).isFalse();
    }

    @Test
    void 약한_신호가_서로_다르면_충돌이다() {
        // 순번은 2인데 이름은 첫 주 자료(수업 소개)다.
        MaterialInput m = material(1, "2.수업소개.pdf", null, null);

        Suggestion s = WeekSuggestionEngine.suggest("과목", List.of(m), Map.of()).get(1L);

        assertThat(s.confidence()).isEqualTo(Confidence.CONFLICT);
        assertThat(s.options()).extracting(Option::week).containsExactly(1, 2);
    }

    @Test
    void 일정_구간_하나의_주차_표기는_실제_주차로_쓰지_않는다() {
        MaterialInput lecture = material(1, "소켓 강의.pdf", null, null,
                section(1, "3주차 소켓 처리하기", "3주차", List.of("SCHEDULE"), null),
                concept(2, "소켓 주소 구조체"));

        Map<Long, Suggestion> s = WeekSuggestionEngine.suggest("네트워크", List.of(lecture), Map.of());

        assertThat(s).doesNotContainKey(1L);
    }

    @Test
    void 분석이_읽은_자기_주차는_강한_신호다() {
        MaterialInput m = material(1, "lecture.pdf", null, "4주차");

        assertWeek(WeekSuggestionEngine.suggest("과목", List.of(m), Map.of()).get(1L), 4, Confidence.HIGH);
    }

    @Test
    void 순번_하나만으로는_확정할_만한_추천이_아니다() {
        MaterialInput m = material(1, "3.AWS.pdf", null, null);

        Suggestion s = WeekSuggestionEngine.suggest("과목", List.of(m), Map.of()).get(1L);

        assertWeek(s, 3, Confidence.LOW);
        assertThat(s.bulkApplicable()).isFalse();
    }

    @Test
    void 강의계획서에_명시_주차가_함께_있으면_충돌로_둔다() {
        MaterialInput m = material(1, "1주차_강의계획서.pdf", null, null,
                section(1, "소개", "1주차", SCHEDULE, null), section(2, "중반", "2~7주차", SCHEDULE, null),
                section(3, "후반", "9~15주차", SCHEDULE, null));

        Suggestion s = WeekSuggestionEngine.suggest("과목", List.of(m), Map.of()).get(1L);

        assertThat(s.confidence()).isEqualTo(Confidence.CONFLICT);
        assertThat(s.options()).containsExactly(new Option(Placement.WEEK, 1), new Option(Placement.COURSE_WIDE, null));
    }

    @Test
    void 다음_시간_예고가_두_자료에_똑같이_맞으면_잇지_않는다() {
        List<MaterialInput> course = List.of(
                material(1, "리눅스.pdf", "리눅스 (1주차)", null,
                        section(1, "다음 시간", null, List.of("SCHEDULE"), "다음 시간 • Docker")),
                material(2, "Docker 기초.pdf", null, null, concept(2, "Docker 설치")),
                material(3, "Docker 심화.pdf", null, null, concept(3, "Docker 네트워크")));

        Map<Long, Suggestion> s = WeekSuggestionEngine.suggest("과목", course, Map.of());

        assertThat(s).doesNotContainKeys(2L, 3L);
    }

    @Test
    void 다음_주소_같은_말은_예고가_아니다() {
        MaterialInput m = material(1, "a.pdf", null, null,
                section(1, "접속", null, List.of("EXAMPLE"), "다음 주소로 접속: http://AWS"));

        assertThat(WeekSuggestionEngine.nextHint(m)).isNull();
    }

    private static void assertWeek(Suggestion s, int week, Confidence confidence) {
        assertThat(s).as("추천이 있어야 한다").isNotNull();
        assertThat(s.placement()).isEqualTo(Placement.WEEK);
        assertThat(s.week()).isEqualTo(week);
        assertThat(s.confidence()).isEqualTo(confidence);
    }

    private static List<SignalKind> kinds(Suggestion s) {
        return s.signals().stream().map(WeekSuggestionEngine.Signal::kind).toList();
    }
}
