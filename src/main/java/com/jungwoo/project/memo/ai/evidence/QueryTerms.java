package com.jungwoo.project.memo.ai.evidence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 상담 발화에서 자료를 찾을 검색어를 만든다.
 *
 * <p>현재 발화만 보지 않는다. "자료에 있는데"·"그 파일 다시 봐"처럼 대상이 앞 발화에 있는 후속 말은 그 자체로는 찾을 말이
 * 없다 — 앞선 사용자 발화와 직전 AI 질문의 말을 낮은 가중치로 함께 쓴다. 그래서 문구별 예외 없이 앞선 목적·대상이 이어진다.
 *
 * <p>개념 확장({@link #CONCEPTS})은 학사 운영 용어의 동의어다(시험 → 중간고사·기말고사·평가). 강의계획서는 "시험"이라고 쓰지
 * 않고 "중간고사"라고 쓰는 일이 흔해서, 말 그대로만 찾으면 비교 대상이 조용히 빠진다. 확장으로 못 찾는 것은 모델이 추가 읽기
 * (검색어 직접 지정)로 보충한다.
 */
public final class QueryTerms {

    /** 검색어 하나의 가중치. 현재 발화 > 개념 확장 > 앞선 발화. */
    public record Term(String text, double weight) {
    }

    /**
     * 개념 묶음. 키가 발화의 어느 낱말에 들어 있으면 값 전체를 검색어로 더한다. 묶음 이름은 "무엇을 찾는 중인가"를 서버가
     * 아는 데에도 쓴다(일정·평가 구간 목록을 함께 실을지).
     */
    static final Map<String, Concept> CONCEPTS = new LinkedHashMap<>();

    public enum Concept { EXAM, ASSIGNMENT, CLASS_TIME, SCHEDULE, LAB, SYLLABUS }

    private static final Map<Concept, List<String>> EXPANSIONS = Map.of(
            Concept.EXAM, List.of("시험", "중간고사", "기말고사", "고사", "평가", "퀴즈", "exam", "midterm", "final"),
            Concept.ASSIGNMENT, List.of("과제", "제출", "마감", "기한", "레포트", "보고서", "형식", "assignment", "homework", "due"),
            Concept.CLASS_TIME, List.of("시간표", "요일", "교시", "강의실", "강의시간", "수업시간", "수업"),
            Concept.SCHEDULE, List.of("일정", "주차", "날짜", "일시", "기간"),
            Concept.LAB, List.of("실습", "예제", "환경", "설치", "실행", "lab"),
            Concept.SYLLABUS, List.of("강의계획서", "수업계획서", "계획서", "syllabus", "주차", "평가"));

    static {
        for (String k : List.of("시험", "고사", "중간", "기말", "퀴즈", "평가")) {
            CONCEPTS.put(k, Concept.EXAM);
        }
        for (String k : List.of("과제", "제출", "마감", "레포트", "보고서", "숙제", "기한")) {
            CONCEPTS.put(k, Concept.ASSIGNMENT);
        }
        for (String k : List.of("시간표", "교시", "강의실", "요일")) {
            CONCEPTS.put(k, Concept.CLASS_TIME);
        }
        for (String k : List.of("일정", "주차", "언제", "날짜", "며칠")) {
            CONCEPTS.put(k, Concept.SCHEDULE);
        }
        for (String k : List.of("실습", "lab", "예제")) {
            CONCEPTS.put(k, Concept.LAB);
        }
        for (String k : List.of("강의계획서", "계획서", "syllabus")) {
            CONCEPTS.put(k, Concept.SYLLABUS);
        }
    }

    /** 뒤에 붙은 조사·어미. 긴 것부터 떼어 본다. 떼고 남은 말이 두 글자 미만이면 떼지 않는다. */
    private static final List<String> SUFFIXES = List.of(
            "에서부터", "으로부터", "에서는", "까지는", "부터는", "에서", "부터", "까지", "으로", "에게", "한테", "처럼", "보다", "대로",
            "이랑", "하고", "이나", "이고", "인데", "은", "는", "이", "가", "을", "를", "에", "의", "로", "도", "만", "랑", "과", "와");

    /**
     * 자료에 너무 흔하거나 검색 대상이 아닌 말. 동사·부사는 자료에 거의 나오지 않아 걸러지지 않아도 점수에 영향이 적다 — 여기는
     * 자료 본문에도 흔한 명사만 둔다("자료" "파일"은 모든 문서에 걸린다).
     */
    private static final Set<String> STOPWORDS = Set.of(
            "자료", "파일", "내용", "부분", "그거", "이거", "저거", "거기", "여기", "정도", "이제", "거의", "그대로", "직접", "다시",
            "무슨", "어떤", "어느", "뭐부터", "무엇", "제가", "내가", "우리", "지금", "오늘", "그럼", "근데", "그리고", "있는데",
            "있어", "없어", "해줘", "알려줘", "찾아서", "보면", "안돼", "좋을까", "하면", "같이", "그냥", "the", "and");

    private static final Pattern TOKEN = Pattern.compile("[\\p{IsHangul}A-Za-z0-9/.]+");
    private static final Pattern DATE_LIKE = Pattern.compile("^\\d{1,2}[/.]\\d{1,2}$|^\\d{1,2}(월|일)$");

    private final List<Term> terms;
    private final Set<Concept> concepts;
    private final Set<Concept> currentConcepts;

    private QueryTerms(List<Term> terms, Set<Concept> concepts, Set<Concept> currentConcepts) {
        this.terms = terms;
        this.concepts = concepts;
        this.currentConcepts = currentConcepts;
    }

    public List<Term> terms() {
        return terms;
    }

    /** 이번 대화 흐름에서 찾는 것의 종류(앞선 발화 포함). */
    public Set<Concept> concepts() {
        return concepts;
    }

    /** 현재 발화만으로 드러난 종류. */
    public Set<Concept> currentConcepts() {
        return currentConcepts;
    }

    public boolean isEmpty() {
        return terms.isEmpty();
    }

    /** 일정·평가·제출·수업 시간처럼 날짜가 핵심인 질문인가. */
    public boolean asksSchedule() {
        return concepts.contains(Concept.EXAM) || concepts.contains(Concept.ASSIGNMENT)
                || concepts.contains(Concept.CLASS_TIME) || concepts.contains(Concept.SCHEDULE)
                || concepts.contains(Concept.SYLLABUS);
    }

    /**
     * 과목 이름 같은 낱말의 가중치를 낮춘 사본. 과목 이름은 "어느 과목인가"(범위)를 말할 뿐 내용이 아니다 — 그 과목 자료의
     * 거의 모든 쪽에 들어 있어 그대로 두면 모든 쪽이 같은 점수로 걸린다. 범위는 과목 우선순위가 따로 맡는다.
     */
    public QueryTerms withScopeWords(java.util.Collection<String> scopeWords, double weight) {
        Set<String> scope = new java.util.HashSet<>();
        for (String w : scopeWords) {
            if (w != null) {
                scope.add(w.replaceAll("\\s+", "").toLowerCase(Locale.ROOT));
            }
        }
        List<Term> out = new ArrayList<>();
        for (Term t : terms) {
            out.add(scope.contains(t.text()) ? new Term(t.text(), Math.min(t.weight(), weight)) : t);
        }
        return new QueryTerms(out, concepts, currentConcepts);
    }

    public List<String> texts() {
        return terms.stream().map(Term::text).toList();
    }

    /**
     * @param current        이번 사용자 발화
     * @param priorUser      이 대화의 앞선 사용자 발화(오래된 것부터)
     * @param priorAssistant 직전 AI 답변(질문 속 대상어를 이어받기 위해)
     * @param extra          모델이 추가 읽기로 지정한 검색어(가중치 1.0)
     */
    public static QueryTerms of(String current, List<String> priorUser, String priorAssistant, List<String> extra) {
        Map<String, Double> weights = new LinkedHashMap<>();
        Set<Concept> concepts = new LinkedHashSet<>();
        Set<Concept> currentConcepts = new LinkedHashSet<>();
        add(weights, concepts, currentConcepts, current, 1.0, true);
        if (extra != null) {
            for (String e : extra) {
                add(weights, concepts, currentConcepts, e, 1.0, true);
            }
        }
        List<String> prior = priorUser == null ? List.of() : priorUser;
        for (int i = prior.size() - 1, age = 0; i >= 0 && age < 4; i--, age++) {
            add(weights, concepts, null, prior.get(i), 0.6 - age * 0.1, false);
        }
        add(weights, concepts, null, priorAssistant, 0.4, false);
        for (Concept c : concepts) {
            double w = currentConcepts.contains(c) ? 0.7 : 0.45;
            for (String e : EXPANSIONS.get(c)) {
                weights.merge(e, w, Math::max);
            }
        }
        List<Term> terms = new ArrayList<>();
        weights.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(24)
                .forEach(e -> terms.add(new Term(e.getKey(), e.getValue())));
        return new QueryTerms(terms, concepts, currentConcepts);
    }

    private static void add(Map<String, Double> weights, Set<Concept> concepts, Set<Concept> currentConcepts,
                            String text, double weight, boolean current) {
        if (text == null || text.isBlank() || weight <= 0) {
            return;
        }
        Matcher m = TOKEN.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String raw = m.group().replaceAll("^[/.]+|[/.]+$", "");
            String token = DATE_LIKE.matcher(raw).matches() ? raw : strip(raw);
            if (token.length() < 2 || STOPWORDS.contains(token) || STOPWORDS.contains(raw)
                    || token.chars().allMatch(Character::isDigit)) {
                continue;
            }
            for (Map.Entry<String, Concept> c : CONCEPTS.entrySet()) {
                if (token.contains(c.getKey())) {
                    concepts.add(c.getValue());
                    if (current && currentConcepts != null) {
                        currentConcepts.add(c.getValue());
                    }
                }
            }
            weights.merge(token, weight, Math::max);
        }
    }

    static String strip(String token) {
        for (String suffix : SUFFIXES) {
            if (token.endsWith(suffix) && token.length() - suffix.length() >= 2) {
                return token.substring(0, token.length() - suffix.length());
            }
        }
        return token;
    }
}
