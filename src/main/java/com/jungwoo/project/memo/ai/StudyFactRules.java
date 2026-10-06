package com.jungwoo.project.memo.ai;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 상담 기억의 서버 대조 규칙(모델·DB 없음).
 *
 * <p>인용(quote)이 사용자 발화 안에 있다는 것만으로는 모델이 쓴 문장(text)이 사용자의 말이라는 보장이 없다 — 인용 "Unit 3"에
 * 문장 "Unit 12까지 수업함"을 붙일 수 있다. 그래서 사용자 말로 인정(STATED·SELF_REPORT)하려면 문장 속 숫자가 전부 사용자
 * 발화에 있어야 하고, 단원 연결은 사용자가 그 단원을 실제로 가리켰을 때만 받는다.
 */
public final class StudyFactRules {

    private StudyFactRules() {
    }

    private static final Pattern NUMBER = Pattern.compile("\\d+");
    /** 단원 번호: "Unit 3", "Lesson 10", "Chapter 2", "3과", "3단원", "3장", "제3장". */
    private static final Pattern UNIT_NUMBER = Pattern.compile(
            "(?i)(?:unit|lesson|chapter|part|section|단원)\\s*(\\d{1,3})(?!\\d)|(?<!\\d)(\\d{1,3})\\s*(?:과(?!목)|단원|장)");
    private static final Pattern LEADING_NUMBER = Pattern.compile("^\\s*(?:제\\s*)?(\\d{1,3})(?:[.)\\s]|장|과|단원)");
    private static final Pattern YEAR = Pattern.compile("(?:19|20)\\d{2}");

    /**
     * 문장 속 숫자가 전부 사용자 발화에 있는가. 연도(19xx·20xx)는 날짜를 정규화하며 붙는 것이라 빼고, "09"와 "9"는 같다.
     */
    public static boolean numbersBackedBy(String text, String userMessage) {
        if (text == null) {
            return true;
        }
        Set<Integer> said = numbersOf(userMessage);
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            String token = m.group();
            if (YEAR.matcher(token).matches()) {
                continue;
            }
            if (token.length() > 6 || !said.contains(Integer.parseInt(token))) {
                return false;
            }
        }
        return true;
    }

    private static Set<Integer> numbersOf(String s) {
        Set<Integer> out = new LinkedHashSet<>();
        if (s == null) {
            return out;
        }
        Matcher m = NUMBER.matcher(s);
        while (m.find()) {
            if (m.group().length() <= 6) {
                out.add(Integer.parseInt(m.group()));
            }
        }
        return out;
    }

    /** 글에서 단원 표기와 함께 나온 번호 전부("Unit 3", "3과", "Lesson 12"). 앞머리 번호("3. …")는 보지 않는다. */
    public static java.util.Set<Integer> markedUnitNumbers(String text) {
        java.util.Set<Integer> out = new java.util.LinkedHashSet<>();
        if (text == null) {
            return out;
        }
        Matcher m = UNIT_NUMBER.matcher(text);
        while (m.find()) {
            out.add(Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2)));
        }
        return out;
    }

    /** 단원 제목의 번호("Unit 10 What's your name?" → 10). 없으면 null. */
    public static Integer unitNumberOf(String title) {
        if (title == null) {
            return null;
        }
        Matcher m = UNIT_NUMBER.matcher(title);
        if (m.find()) {
            return Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2));
        }
        Matcher lead = LEADING_NUMBER.matcher(title);
        return lead.find() ? Integer.parseInt(lead.group(1)) : null;
    }

    /**
     * 사용자 발화가 이 단원을 가리키는가. 번호가 있는 단원은 그 번호가 단원 표기와 함께("Unit 3", "3과") 나와야 하고,
     * 번호가 없으면 제목의 핵심어(3자 이상) 하나가 나와야 한다. 제목이 같은 다른 번호 단원을 가리킨 말은 인정하지 않는다.
     */
    public static boolean mentionsTopic(String userMessage, String topicTitle, Integer tocSeq) {
        if (userMessage == null || topicTitle == null) {
            return false;
        }
        Integer number = unitNumberOf(topicTitle);
        if (number == null) {
            number = tocSeq;
        }
        if (number != null) {
            Matcher m = UNIT_NUMBER.matcher(userMessage);
            while (m.find()) {
                int said = Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2));
                if (said == number) {
                    return true;
                }
            }
            return false;
        }
        String said = UserContextService.key(userMessage);
        for (String word : topicTitle.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (word.length() >= 3 && said.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /** 부정·미완·질문·가정 표지. 같은 숫자를 쓴 "아직 못 끝냈어"·"나가면 좋겠다"·"했나?"를 현재 상태로 보지 않는다. */
    private static final Pattern NOT_ASSERTED = Pattern.compile(
            // "면"은 띄어쓰기·뒤 낱말과 무관하게 조건으로 본다("나가면시험…"). 명사(화면·장면…)만 뺀다.
            "못|안 |않|아직|\\?|[을일할]까|까요|나요|(?<!화|장|측|표|방|전|이)면(?!접)|거나|지도|없|모르|듯|같아|같은데"
);

    /** 여전히 막혔다는 말 — 해결 판정에서만 본다(진도와 막힘을 한 문장에 말해도 진도는 그대로 받게). */
    private static final Pattern STILL_STUCK = Pattern.compile("더 헷갈|더 어려|헷갈려|헷갈리|막혀|막힌다|어려워|모르겠");

    /**
     * 진도·시험 범위를 자동으로 바꿀 만큼 인용이 그 주장을 직접 담는가: 문장의 숫자가 전부 <b>인용 안에</b> 있고(발화의 다른
     * 곳이 아니라), 인용에 부정·미완·질문·가정 표지가 없다.
     */
    public static boolean assertsCurrentState(String text, String quote) {
        if (quote == null || quote.isBlank()) {
            return false;
        }
        return numbersBackedBy(text, quote) && !NOT_ASSERTED.matcher(quote).find();
    }

    private static final Pattern PROGRESS_CUE = Pattern.compile("까지|나갔|나가|배웠|배운|진도|했어|했다|끝났|끝냈|들었|수업");
    private static final Pattern EXAM_CUE = Pattern.compile("시험|범위|중간|기말|퀴즈|평가");
    private static final Pattern DIFFICULTY_CUE = Pattern.compile("막|어려|헷갈|모르|못 하|못하|안 돼|안돼|약해|자신 없|힘들");
    // "이제"만으로는 해결이 아니다("이제 더 헷갈려").
    private static final Pattern RESOLVED_CUE = Pattern.compile("풀었|해결했|해결됐|알겠|이해했|이해돼|수 있|할 줄|감 잡|되네");

    /**
     * 사용자가 실제로 쓴 말(인용)에 그 종류의 단서가 있는가. 진도·시험 범위·막힘·해결은 대체·닫기를 일으키므로 단서가 있어야
     * 그 종류로 받는다 — 외부 데이터(단원 제목 등)에 휘둘린 모델이 "Unit 3이 어려워"를 진도로 적어도 진도가 되지 않게.
     * 그 밖의 종류는 단서를 보지 않는다.
     */
    public static boolean kindBackedBy(com.jungwoo.project.memo.ai.domain.FactKind kind, String quote) {
        if (kind == null || quote == null) {
            return kind == null;
        }
        return switch (kind) {
            case PROGRESS -> PROGRESS_CUE.matcher(quote).find();
            case EXAM_SCOPE -> EXAM_CUE.matcher(quote).find();
            case DIFFICULTY -> DIFFICULTY_CUE.matcher(quote).find();
            // 해결은 긍정적인 해결 주장이어야 한다 — 부정·미완·질문·가정이나 여전히 막힌다는 말이면 아니다.
            case RESOLVED -> RESOLVED_CUE.matcher(quote).find() && !NOT_ASSERTED.matcher(quote).find()
                    && !STILL_STUCK.matcher(quote).find();
            default -> true;
        };
    }

    /** 발화에 진도·시험·막힘·해결 신호가 분명한가(모델이 기억을 빠뜨렸을 때 보조 추출을 부를지). */
    private static final Pattern STRONG_SIGNAL = Pattern.compile(
            "까지 (나갔|배웠|했)|진도|시험 범위|막혀|막혔|헷갈|어려워|풀었|해결했|이해했|이제 .{0,20}수 있");

    public static boolean strongStudySignal(String message) {
        return message != null && STRONG_SIGNAL.matcher(message).find();
    }
}
