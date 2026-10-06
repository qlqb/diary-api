package com.jungwoo.project.memo.learning.week;

import com.jungwoo.project.memo.material.domain.SectionRole;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 자료마다 "몇 주차 자료일 것 같은가"를 추천한다. 저장하지 않는 순수 계산이다.
 *
 * <p>★ 추천은 추천이다. 이 클래스는 확정 관계를 만들지 않는다 — 사용자가 적용하거나 직접 옮긴 것만
 * {@code material_week_assignments}에 들어가고, 학습 지도는 그것만 본다.
 *
 * <p>신호는 셋으로 나뉜다.
 * <ul>
 *   <li><b>강한 신호</b> — 자료가 스스로 밝힌 주차: 파일명의 "N주차/N주", 파일 속성 제목의 "N주차",
 *       자료 분석이 읽은 "문서가 스스로 말한 주차". 하나면 HIGH, 서로 다르면 CONFLICT.</li>
 *   <li><b>약한 신호</b> — 파일명 순번("3."), 첫 주 자료로 보이는 이름(수업 소개), 다른 자료와의 관계
 *       (앞 자료의 "다음 시간" 예고, 실습 파일과 강의 자료의 구간 이름). 둘 이상이 같은 주를 가리키면
 *       MEDIUM, 하나면 LOW, 서로 다르면 CONFLICT.</li>
 *   <li><b>전체 참고</b> — 여러 주차의 예정 진도를 담은 자료(강의계획서). 그 주차들은 <b>예정</b>이고
 *       이 자료가 그 주에 쓰인다는 뜻이 아니다. 특정 주차가 아니라 "전체 참고자료"를 추천한다.</li>
 * </ul>
 *
 * <p>일정·운영 안내 구간(SCHEDULE/ADMIN)의 주차 표기는 실제 주차 신호로 쓰지 않는다. 강의계획서의
 * "3주차 소켓 처리하기"가 실제 3주차 자료를 밀어낸 것이 이 작업의 출발점이다.
 *
 * <p>관계 신호는 <b>이미 주차가 선</b> 자료(사용자 확정, 또는 HIGH·MEDIUM 추천)에서만 번진다. 약한 신호끼리
 * 서로를 근거로 삼아 확신이 부풀지 않게, 앞 단계에서 선 자료만 다음 단계의 근거가 된다.
 */
public final class WeekSuggestionEngine {

    public enum Placement { WEEK, COURSE_WIDE, UNASSIGNED }

    public enum Confidence { HIGH, MEDIUM, LOW, CONFLICT }

    public enum SignalKind {
        FILENAME_WEEK(true), DOCUMENT_TITLE_WEEK(true), CONTENT_WEEK(true),
        FILENAME_NUMBER(false), FIRST_WEEK_NAME(false), NEXT_LECTURE(false), PRACTICE_OF(false),
        COURSE_PLAN(false);

        private final boolean strong;

        SignalKind(boolean strong) {
            this.strong = strong;
        }

        public boolean strong() {
            return strong;
        }
    }

    /** 근거 하나. detail은 사람이 읽는 짧은 말이다 — 모델의 자유 서술이 아니라 신호 그 자체를 적는다. */
    public record Signal(SignalKind kind, Placement placement, Integer week, String detail) {
    }

    public record Option(Placement placement, Integer week) {
    }

    /**
     * @param placement  추천 자리. CONFLICT면 options 중 하나를 사용자가 고른다
     * @param week       placement=WEEK일 때의 주차. CONFLICT면 null
     * @param options    CONFLICT일 때의 후보(두 개 이상). 그 밖에는 하나
     */
    public record Suggestion(Placement placement, Integer week, Confidence confidence, List<Option> options,
                             List<Signal> signals) {

        /** "추천대로 적용"이 한꺼번에 옮겨도 되는가. 충돌·약한 추천은 사용자가 하나씩 본다. */
        public boolean bulkApplicable() {
            return confidence == Confidence.HIGH || confidence == Confidence.MEDIUM;
        }
    }

    /**
     * @param roles 분석이 붙인 구간 역할(SectionRole 이름)
     */
    public record SectionInput(Long sectionId, String title, String label, List<String> roles, String excerpt) {

        boolean scheduleOrAdmin() {
            return roles != null && roles.stream().map(SectionRole::parseOne).filter(Objects::nonNull)
                    .anyMatch(r -> r == SectionRole.SCHEDULE || r == SectionRole.ADMIN);
        }

        boolean schedule() {
            return roles != null && roles.stream().map(SectionRole::parseOne).anyMatch(r -> r == SectionRole.SCHEDULE);
        }
    }

    /**
     * @param documentTitle    파일 속성의 제목(PDF·PPTX). 없으면 null
     * @param contentWeekLabel 자료 분석이 읽은 "문서가 스스로 말한 주차"(예: "2주차"). 없으면 null
     * @param sections         분석 구간. 문서 순서대로
     */
    public record MaterialInput(Long materialId, String filename, String documentTitle, String contentWeekLabel,
                                List<SectionInput> sections) {
    }

    /** 확정된 자리. 관계 신호의 닻이 된다. */
    public record Confirmed(Placement placement, Set<Integer> weeks) {
    }

    /** 이만큼 여러 주차의 예정 진도가 적혀 있으면 강의계획서로 본다. */
    static final int COURSE_PLAN_MIN_WEEKS = 4;
    static final int COURSE_PLAN_MIN_SECTIONS = 3;

    /** "다음 시간", "다음 강의", "다음 주". "다음 주소"·"다음 주요"는 아니다. */
    private static final Pattern NEXT_HINT = Pattern.compile("다음\\s*(?:시간|강의|수업|차시|주차|주(?![소식요]))");
    /**
     * 첫 주 자료로 보이는 이름. "수업 개요"·"강의 개요"는 넣지 않는다 — 매 강의 첫 장이 "AWS 구성하기 수업 개요"다.
     */
    private static final Pattern FIRST_WEEK_NAME = Pattern.compile(
            "(?i)오리엔테이션|orientation|(?:수업|강의|과목)\\s*소개|과목\\s*개요");
    private static final Pattern WORD = Pattern.compile("[A-Za-z][A-Za-z0-9]*|[가-힣]+");

    /** 주차 판단과 상관없는 흔한 말. 이 말만 겹쳐서는 두 자료가 이어진다고 보지 않는다. */
    private static final Set<String> STOP_WORDS = Set.of(
            "실습", "수업", "강의", "다음", "시간", "소개", "기초", "사용", "개요", "내용", "안내", "자료", "스크립트",
            "문제", "과제", "정리", "이번", "오늘", "주차", "학생", "교수", "예제", "연습", "복습", "요약", "추가",
            "구축", "하기", "관련", "이론", "기본", "방법", "학습", "과목", "프로그래밍");
    private static final Set<String> STOP_LATIN = Set.of(
            "pdf", "ppt", "pptx", "hwp", "sh", "ipynb", "the", "and", "for", "lab", "part", "ch", "chap", "week");

    private WeekSuggestionEngine() {
    }

    /**
     * @param courseTitle 프로젝트 이름. 과목명 낱말은 관계 판단에서 뺀다(모든 자료에 들어 있다)
     * @param materials   이 프로젝트에 연결된 자료
     * @param confirmed   자료별 확정 자리. 없으면 비어 있다
     * @return 자료별 추천. 근거가 하나도 없으면 그 자료는 결과에 없다
     */
    public static Map<Long, Suggestion> suggest(String courseTitle, List<MaterialInput> materials,
                                                Map<Long, Confirmed> confirmed) {
        Set<String> courseBigrams = new LinkedHashSet<>();
        for (String word : words(courseTitle)) {
            courseBigrams.addAll(bigrams(word));
        }

        Map<Long, List<Signal>> base = new LinkedHashMap<>();
        Map<Long, Boolean> coursePlan = new HashMap<>();
        for (MaterialInput m : materials) {
            List<Signal> signals = baseSignals(m);
            base.put(m.materialId(), signals);
            coursePlan.put(m.materialId(), signals.stream().anyMatch(s -> s.kind() == SignalKind.COURSE_PLAN));
        }

        List<Edge> nextEdges = nextLectureEdges(materials, coursePlan, courseBigrams);
        Map<Long, PracticeEdge> practiceEdges = practiceEdges(materials, base, coursePlan, courseBigrams);
        Map<Long, MaterialInput> byId = new LinkedHashMap<>();
        materials.forEach(m -> byId.put(m.materialId(), m));

        // 1단계: 자기 신호만으로. 확정된 자료는 확정 자리가 곧 닻이다.
        Map<Long, Suggestion> result = new LinkedHashMap<>();
        for (MaterialInput m : materials) {
            putIfPresent(result, m.materialId(), decide(base.get(m.materialId())));
        }
        Map<Long, Integer> anchors = anchors(materials, result, confirmed);

        // 2단계부터: 앞 단계에서 선 자료만 근거로 관계 신호를 더한다. 새로 서는 자료가 없으면 멈춘다.
        Map<Long, List<Signal>> relations = new HashMap<>();
        for (int round = 0; round < materials.size(); round++) {
            Map<Long, List<Signal>> next = new HashMap<>();
            for (Edge e : nextEdges) {
                Integer from = anchors.get(e.from());
                Integer to = anchors.get(e.to());
                if (from != null && from + 1 <= WeekExpressions.MAX_WEEK && !isConfirmed(confirmed, e.to())) {
                    next.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(new Signal(SignalKind.NEXT_LECTURE,
                            Placement.WEEK, from + 1, "\"" + shortName(byId.get(e.from())) + "\"(" + from
                            + "주차)의 다음 시간 안내가 이 자료를 예고함(" + e.words() + ")"));
                }
                if (to != null && to - 1 >= 1 && !isConfirmed(confirmed, e.from())) {
                    next.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(new Signal(SignalKind.NEXT_LECTURE,
                            Placement.WEEK, to - 1, "다음 시간 안내가 \"" + shortName(byId.get(e.to())) + "\"(" + to
                            + "주차)를 예고함(" + e.words() + ")"));
                }
            }
            for (PracticeEdge e : practiceEdges.values()) {
                Integer target = anchors.get(e.target());
                if (target != null && !isConfirmed(confirmed, e.material())) {
                    next.computeIfAbsent(e.material(), k -> new ArrayList<>()).add(new Signal(SignalKind.PRACTICE_OF,
                            Placement.WEEK, target, "\"" + shortName(byId.get(e.target())) + "\"(" + target
                            + "주차)의 \"" + e.sectionTitle() + "\" 구간과 이름이 맞음"));
                }
            }
            relations = next;
            Map<Long, Suggestion> round2 = new LinkedHashMap<>();
            for (MaterialInput m : materials) {
                List<Signal> all = new ArrayList<>(base.get(m.materialId()));
                all.addAll(relations.getOrDefault(m.materialId(), List.of()));
                putIfPresent(round2, m.materialId(), decide(all));
            }
            Map<Long, Integer> nextAnchors = anchors(materials, round2, confirmed);
            result = round2;
            if (nextAnchors.equals(anchors)) {
                break;
            }
            anchors = nextAnchors;
        }
        return result;
    }

    // ===== 한 자료의 신호 =====

    static List<Signal> baseSignals(MaterialInput m) {
        List<Signal> signals = new ArrayList<>();
        List<Integer> named = WeekExpressions.filenameWeeks(m.filename());
        if (named.size() == 1) {
            signals.add(new Signal(SignalKind.FILENAME_WEEK, Placement.WEEK, named.get(0),
                    "파일명: " + named.get(0) + "주차"));
        }
        List<Integer> titled = WeekExpressions.weeksIn(m.documentTitle());
        if (titled.size() == 1) {
            signals.add(new Signal(SignalKind.DOCUMENT_TITLE_WEEK, Placement.WEEK, titled.get(0),
                    "파일 속성 제목: \"" + cut(m.documentTitle(), 80) + "\""));
        }
        List<Integer> content = WeekExpressions.weeksIn(m.contentWeekLabel());
        if (content.size() == 1) {
            signals.add(new Signal(SignalKind.CONTENT_WEEK, Placement.WEEK, content.get(0),
                    "자료 분석: 문서가 스스로 " + content.get(0) + "주차라고 밝힘"));
        }
        Integer number = WeekExpressions.leadingNumber(m.filename());
        if (number != null && named.isEmpty()) {
            signals.add(new Signal(SignalKind.FILENAME_NUMBER, Placement.WEEK, number,
                    "파일명 순번: " + number + "."));
        }
        String firstTitle = m.sections() == null || m.sections().isEmpty() ? null : m.sections().get(0).title();
        if (FIRST_WEEK_NAME.matcher(WeekExpressions.stem(m.filename())).find()
                || (firstTitle != null && FIRST_WEEK_NAME.matcher(firstTitle).find())) {
            signals.add(new Signal(SignalKind.FIRST_WEEK_NAME, Placement.WEEK, 1,
                    "수업 소개·오리엔테이션 자료(보통 첫 주)"));
        }
        List<Integer> planned = plannedWeeks(m);
        if (!planned.isEmpty()) {
            // 강의계획서의 순번·"과목 개요" 같은 약한 단서는 버린다 — 이 자료는 특정 주의 수업 자료가 아니다.
            // 명시적 주차(강한 신호)는 남겨 충돌로 보여 준다.
            signals.removeIf(s -> !s.kind().strong());
            signals.add(new Signal(SignalKind.COURSE_PLAN, Placement.COURSE_WIDE, null,
                    "여러 주차의 예정 진도가 적힌 자료(" + WeekExpressions.describe(planned) + ") — 강의계획서"));
        }
        return signals;
    }

    /**
     * 일정 구간에 적힌 예정 주차. 이만큼 넓게 퍼져 있어야 강의계획서로 본다 — "지난 2주차 자료" 한 줄로는 아니다.
     * 이 주차들은 예정 진도다. 실제 주차 칸에 넣지 않는다.
     */
    static List<Integer> plannedWeeks(MaterialInput m) {
        if (m.sections() == null) {
            return List.of();
        }
        TreeSet<Integer> weeks = new TreeSet<>();
        int sections = 0;
        for (SectionInput s : m.sections()) {
            if (!s.schedule()) {
                continue;
            }
            Set<Integer> here = new TreeSet<>(WeekExpressions.weeksIn(s.label()));
            here.addAll(WeekExpressions.weeksIn(s.title()));
            if (!here.isEmpty()) {
                sections++;
                weeks.addAll(here);
            }
        }
        return sections >= COURSE_PLAN_MIN_SECTIONS && weeks.size() >= COURSE_PLAN_MIN_WEEKS
                ? List.copyOf(weeks) : List.of();
    }

    /** 신호 묶음 → 추천 하나. */
    static Suggestion decide(List<Signal> signals) {
        if (signals == null || signals.isEmpty()) {
            return null;
        }
        boolean coursePlan = signals.stream().anyMatch(s -> s.kind() == SignalKind.COURSE_PLAN);
        TreeSet<Integer> strong = new TreeSet<>();
        signals.stream().filter(s -> s.kind().strong()).forEach(s -> strong.add(s.week()));

        if (coursePlan) {
            if (strong.isEmpty()) {
                return new Suggestion(Placement.COURSE_WIDE, null, Confidence.HIGH,
                        List.of(new Option(Placement.COURSE_WIDE, null)), signals);
            }
            List<Option> options = new ArrayList<>();
            strong.forEach(w -> options.add(new Option(Placement.WEEK, w)));
            options.add(new Option(Placement.COURSE_WIDE, null));
            return new Suggestion(null, null, Confidence.CONFLICT, options, signals);
        }
        if (strong.size() > 1) {
            return conflict(strong, signals);
        }
        if (strong.size() == 1) {
            int week = strong.first();
            return new Suggestion(Placement.WEEK, week, Confidence.HIGH, List.of(new Option(Placement.WEEK, week)),
                    signals);
        }
        TreeSet<Integer> weak = new TreeSet<>();
        Set<SignalKind> kinds = new LinkedHashSet<>();
        for (Signal s : signals) {
            if (s.placement() == Placement.WEEK && s.week() != null) {
                weak.add(s.week());
                kinds.add(s.kind());
            }
        }
        if (weak.isEmpty()) {
            return null;
        }
        if (weak.size() > 1) {
            return conflict(weak, signals);
        }
        int week = weak.first();
        return new Suggestion(Placement.WEEK, week, kinds.size() >= 2 ? Confidence.MEDIUM : Confidence.LOW,
                List.of(new Option(Placement.WEEK, week)), signals);
    }

    private static Suggestion conflict(Collection<Integer> weeks, List<Signal> signals) {
        return new Suggestion(null, null, Confidence.CONFLICT,
                weeks.stream().map(w -> new Option(Placement.WEEK, w)).toList(), signals);
    }

    /** 이번 단계의 닻: 확정 주차(하나일 때) → HIGH·MEDIUM 추천 주차. 확정이 여러 주차면 닻으로 쓰지 않는다. */
    private static Map<Long, Integer> anchors(List<MaterialInput> materials, Map<Long, Suggestion> suggestions,
                                             Map<Long, Confirmed> confirmed) {
        Map<Long, Integer> anchors = new HashMap<>();
        for (MaterialInput m : materials) {
            Confirmed c = confirmed == null ? null : confirmed.get(m.materialId());
            if (c != null) {
                if (c.placement() == Placement.WEEK && c.weeks() != null && c.weeks().size() == 1) {
                    anchors.put(m.materialId(), c.weeks().iterator().next());
                }
                continue;
            }
            Suggestion s = suggestions.get(m.materialId());
            if (s != null && s.placement() == Placement.WEEK && s.bulkApplicable()) {
                anchors.put(m.materialId(), s.week());
            }
        }
        return anchors;
    }

    private static boolean isConfirmed(Map<Long, Confirmed> confirmed, Long materialId) {
        return confirmed != null && confirmed.containsKey(materialId);
    }

    // ===== 자료 사이의 관계 =====

    /** A의 "다음 시간" 예고가 B를 가리킨다. */
    record Edge(Long from, Long to, String words) {
    }

    /** 이름이 다른 자료의 구간 이름과 맞는 실습 파일. */
    record PracticeEdge(Long material, Long target, String sectionTitle) {
    }

    /**
     * "다음 시간 • 리눅스 사용 기초 및 AWS" 같은 예고 구간에서 낱말을 뽑아, 다른 자료의 이름·첫 제목과 맞는
     * 자료가 <b>하나</b>일 때만 잇는다. 둘 이상이 비기면 잇지 않는다 — 고를 근거가 없다.
     */
    static List<Edge> nextLectureEdges(List<MaterialInput> materials, Map<Long, Boolean> coursePlan,
                                       Set<String> courseBigrams) {
        List<Edge> edges = new ArrayList<>();
        for (MaterialInput from : materials) {
            if (Boolean.TRUE.equals(coursePlan.get(from.materialId()))) {
                continue; // 강의계획서의 "다음 주" 표기는 예정이다
            }
            String hint = nextHint(from);
            if (hint == null) {
                continue;
            }
            List<String> hintWords = meaningfulWords(hint, courseBigrams);
            Long best = null;
            String bestWords = null;
            int bestScore = 0;
            boolean tie = false;
            for (MaterialInput to : materials) {
                if (to.materialId().equals(from.materialId()) || Boolean.TRUE.equals(coursePlan.get(to.materialId()))) {
                    continue;
                }
                List<String> matched = matchedWords(hintWords, identityWords(to, courseBigrams), courseBigrams);
                if (matched.size() > bestScore) {
                    best = to.materialId();
                    bestWords = String.join(", ", matched);
                    bestScore = matched.size();
                    tie = false;
                } else if (!matched.isEmpty() && matched.size() == bestScore) {
                    tie = true;
                }
            }
            if (best != null && !tie) {
                edges.add(new Edge(from.materialId(), best, bestWords));
            }
        }
        return edges;
    }

    /**
     * 주차 단서가 전혀 없는 자료(실습 스크립트 등)의 이름을 다른 자료의 구간 제목과 맞춘다. 맞는 자료가
     * 하나일 때만. 일정·운영 구간과는 맞추지 않는다.
     */
    static Map<Long, PracticeEdge> practiceEdges(List<MaterialInput> materials, Map<Long, List<Signal>> base,
                                                 Map<Long, Boolean> coursePlan, Set<String> courseBigrams) {
        Map<Long, PracticeEdge> edges = new LinkedHashMap<>();
        for (MaterialInput m : materials) {
            boolean hasOwn = base.get(m.materialId()).stream().anyMatch(s -> s.placement() == Placement.WEEK
                    || s.kind() == SignalKind.COURSE_PLAN);
            if (hasOwn) {
                continue;
            }
            List<String> name = meaningfulWords(WeekExpressions.stem(m.filename()), courseBigrams);
            if (name.isEmpty()) {
                continue;
            }
            Long best = null;
            String bestSection = null;
            int bestScore = 0;
            boolean tie = false;
            for (MaterialInput target : materials) {
                if (target.materialId().equals(m.materialId()) || Boolean.TRUE.equals(coursePlan.get(target.materialId()))
                        || target.sections() == null) {
                    continue;
                }
                int score = 0;
                String section = null;
                for (SectionInput s : target.sections()) {
                    if (s.scheduleOrAdmin() || s.title() == null) {
                        continue;
                    }
                    int here = matchedWords(name, meaningfulWords(s.title(), courseBigrams), courseBigrams).size();
                    if (here > score) {
                        score = here;
                        section = s.title();
                    }
                }
                if (score > bestScore) {
                    best = target.materialId();
                    bestSection = section;
                    bestScore = score;
                    tie = false;
                } else if (score > 0 && score == bestScore) {
                    tie = true;
                }
            }
            if (best != null && !tie) {
                edges.put(m.materialId(), new PracticeEdge(m.materialId(), best, cut(bestSection, 60)));
            }
        }
        return edges;
    }

    /** 예고 구간의 글에서 "다음 시간" 뒤의 말. 없으면 null. */
    static String nextHint(MaterialInput m) {
        if (m.sections() == null) {
            return null;
        }
        for (SectionInput s : m.sections()) {
            for (String text : new String[]{s.excerpt(), s.title()}) {
                if (text == null) {
                    continue;
                }
                Matcher matcher = NEXT_HINT.matcher(text);
                if (matcher.find()) {
                    String rest = text.substring(matcher.end()).trim();
                    if (!rest.isEmpty()) {
                        return cut(rest, 200);
                    }
                }
            }
        }
        return null;
    }

    /** 자료를 가리키는 말: 파일명(순번·꼬리 없이), 파일 속성 제목, 첫 두 구간 제목. */
    private static List<String> identityWords(MaterialInput m, Set<String> courseBigrams) {
        StringBuilder sb = new StringBuilder(WeekExpressions.stem(m.filename()));
        if (m.documentTitle() != null) {
            sb.append(' ').append(m.documentTitle());
        }
        if (m.sections() != null) {
            m.sections().stream().limit(2).forEach(s -> sb.append(' ').append(s.title() == null ? "" : s.title()));
        }
        return meaningfulWords(sb.toString(), courseBigrams);
    }

    /** a의 낱말 중 b에 맞는 것. 영문은 같거나 한쪽이 다른 쪽을 품으면, 한글은 의미 있는 두 글자가 겹치면. */
    private static List<String> matchedWords(List<String> a, List<String> b, Set<String> courseBigrams) {
        List<String> matched = new ArrayList<>();
        for (String x : a) {
            for (String y : b) {
                if (matches(x, y, courseBigrams)) {
                    matched.add(x);
                    break;
                }
            }
        }
        return matched;
    }

    private static boolean matches(String x, String y, Set<String> courseBigrams) {
        boolean latinX = isLatin(x);
        if (latinX != isLatin(y)) {
            return false;
        }
        if (latinX) {
            String lx = x.toLowerCase(Locale.ROOT);
            String ly = y.toLowerCase(Locale.ROOT);
            return lx.equals(ly) || (lx.length() >= 3 && ly.contains(lx)) || (ly.length() >= 3 && lx.contains(ly));
        }
        for (String g : bigrams(x)) {
            if (!stopBigram(g, courseBigrams) && y.contains(g)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> meaningfulWords(String text, Set<String> courseBigrams) {
        List<String> out = new ArrayList<>();
        for (String w : words(text)) {
            if (isLatin(w)) {
                String lower = w.toLowerCase(Locale.ROOT);
                if (w.length() >= 2 && !STOP_LATIN.contains(lower) && !lower.matches("v\\d+|s")) {
                    out.add(w);
                }
                continue;
            }
            if (w.length() < 2 || STOP_WORDS.contains(w)) {
                continue;
            }
            boolean meaningful = bigrams(w).stream().anyMatch(g -> !stopBigram(g, courseBigrams));
            if (meaningful && !out.contains(w)) {
                out.add(w);
            }
        }
        return out;
    }

    private static boolean stopBigram(String bigram, Set<String> courseBigrams) {
        if (courseBigrams.contains(bigram)) {
            return true;
        }
        for (String stop : STOP_WORDS) {
            if (stop.contains(bigram)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        Matcher m = WORD.matcher(Normalizer.normalize(text, Normalizer.Form.NFC));
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    private static List<String> bigrams(String word) {
        List<String> out = new ArrayList<>();
        if (word == null || isLatin(word)) {
            return out;
        }
        for (int i = 0; i + 2 <= word.length(); i++) {
            out.add(word.substring(i, i + 2));
        }
        return out;
    }

    private static boolean isLatin(String w) {
        return !w.isEmpty() && w.charAt(0) < 0x80;
    }

    private static String shortName(MaterialInput m) {
        return m == null ? "자료" : cut(m.filename(), 40);
    }

    private static String cut(String text, int max) {
        if (text == null) {
            return null;
        }
        String t = text.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }

    private static void putIfPresent(Map<Long, Suggestion> map, Long id, Suggestion s) {
        if (s != null) {
            map.put(id, s);
        }
    }
}
