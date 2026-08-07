package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.domain.AiMessage;
import com.jungwoo.project.memo.ai.domain.MessageRole;
import com.jungwoo.project.memo.ai.domain.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * 매 사용자 메시지마다 대화 원문 전체를 OpenAI에 보내지 않기 위해, 서버가 고정 크기의
 * 컨텍스트만 구성한다: 저장된 요약(있으면) 1개 + 최근 메시지 N개 + 사용자의 장기 컨텍스트
 * (ACTIVE/STALE만). 일기 원문/전체 실행 기록은 여기 포함하지 않는다 — 화면 범위 데이터는
 * 컨트롤러 레벨에서 focusId로만 참조한다.
 *
 * contextBudgetChars(호출부가 전체 입력 예산에서 현재 사용자 메시지 길이를 뺀 나머지)를
 * 이 서비스가 세 영역(최근 대화/장기 컨텍스트/이전 요약)에 배분하고 렌더링까지 전부 책임진다
 * — AiConversationService는 메시지·Context 내부 구조를 몰라도 된다. 배분·선택 방식은
 * {@link #buildContextBlock(Long, Long, String, int)} 문서 참고.
 *
 * 장기 컨텍스트는 각 줄에 context_id를 함께 내려준다 — 모델이 SUPERSEDE/MARK_STALE/ARCHIVE/
 * CONFIRM 후보를 만들 때 그 id를 targetContextId로 그대로 참조하게 하기 위함이다. SUPERSEDED/
 * ARCHIVED 상태는 이미 대체되었거나 더 이상 쓰지 않는 사실이므로 일반 상담 프롬프트에 보내지
 * 않는다.
 *
 * 오래된 대화를 요약하는 별도 AI 호출은 이번 범위에 없다 (요구사항: 사용자 메시지마다 두
 * 번째 AI 호출을 추가하지 말 것). summary는 컬럼만 두고, 최근 메시지 개수 제한부터
 * 정확히 적용한다.
 */
@Service
@RequiredArgsConstructor
public class ContextSnapshotService {

    /**
     * contextBudgetChars 안에서 세 영역의 기본 배분 비율. 합이 반드시 1.0이어야 한다 —
     * 세 값을 따로 바꿀 때는 항상 함께 맞춰야 한다.
     */
    private static final double RECENT_CONTEXT_RATIO = 0.50;
    private static final double LONG_TERM_CONTEXT_RATIO = 0.35;
    private static final double SUMMARY_CONTEXT_RATIO = 0.15;

    /**
     * 세 섹션을 이어붙일 때 섹션 사이에 넣는 줄바꿈("\n") 자릿수를 미리 빼둔다 — 최대
     * 2번(요약↔최근, 최근↔장기)이라 상한을 2로 고정한다. 실제 배분·선택은 이 값을 뺀
     * effectiveBudget을 기준으로 하므로, 나중에 구분자를 실제로 붙여도 contextBudgetChars를
     * 절대 넘지 않는다.
     */
    private static final int SEPARATOR_RESERVE_CHARS = 2;

    private static final String SUMMARY_HEADER = "[이전 대화 요약]\n";
    private static final String LONG_TERM_HEADER = "[장기 컨텍스트]\n";

    /** 요약을 앞부분만 남기고 자를 때 붙이는 표시. summary는 최근 메시지/장기 Context보다 우선순위가 가장 낮다. */
    private static final String SUMMARY_TRUNCATION_MARKER = "...(이하 생략)...";

    private final AiMessageMapper aiMessageMapper;
    private final UserContextMapper userContextMapper;

    @Value("${ai.context.recent-message-limit:6}")
    private int recentMessageLimit;

    /** 장기 컨텍스트가 토큰 예산을 잡아먹지 않도록 최근 갱신 순으로 이 개수까지만 프롬프트에 담는다. */
    @Value("${ai.context.long-term-limit:30}")
    private int longTermLimit;

    /**
     * 세 영역(최근 대화/장기 컨텍스트/이전 요약)을 contextBudgetChars 안에서 조립한다.
     * contextBudgetChars는 호출부(AiConversationService)가 "전체 입력 예산 - 현재 사용자
     * 메시지 길이"로 이미 계산해 넘긴 값이다 — 현재 사용자 메시지는 이 메서드가 아예 다루지
     * 않으므로 여기서 잘릴 일이 없다.
     *
     * 문자열 전체를 뒤에서부터 substring하는 방식은 쓰지 않는다 — 그 방식은 장기 컨텍스트가
     * 문자열 맨 끝에 있다는 이유만으로 최근 메시지보다 먼저 살아남는 역전이 생겼다(우선순위
     * 위반). 대신 아래 규칙으로 영역별 배분과 선택을 한다:
     *
     * 1) 세 영역 각각의 "전체 필요 길이"(자르지 않았을 때 렌더링 길이, 섹션 헤더 포함)를 먼저
     *    구한다.
     * 2) 기본 배분: 최근 50% / 장기 35% / 요약 15%(위 RATIO 상수)를 각 영역의 필요 길이로
     *    상한을 씌운다(min) — 필요한 양보다 더 배분하지 않는다.
     * 3) 남는 예산(기본 배분 후 안 쓴 만큼)은 최근 대화 -> 장기 컨텍스트 -> 이전 요약 순서로
     *    재배분한다 — 우선순위가 높은 영역이 남는 공간을 먼저 가져간다.
     * 4) 최종 배분액 안에서 실제로 무엇을 담을지 고른다:
     *    - 최근 대화: 메시지 하나를 절대 문자 단위로 자르지 않는다. 가장 최신 메시지부터
     *      역순으로 누적하다가, 다음 메시지(더 오래된 메시지)를 통째로 넣으면 배분액을
     *      넘는 시점에서 멈춘다 — 그 메시지와 그보다 오래된 메시지는 전부 제외한다. 최종
     *      렌더링 순서는 항상 원래 대화 순서(오래된 -> 최신)를 유지한다.
     *    - 장기 컨텍스트: 마찬가지로 행 하나를 절대 자르지 않는다. UserContextMapper가 이미
     *      최근 갱신 순으로 내려주므로 그 순서 그대로 앞에서부터 누적하다가 배분액을 넘기는
     *      첫 행에서 멈춘다(그 뒤는 더 오래된 것들이므로 제외).
     *    - 이전 요약: 셋 중 우선순위가 가장 낮다 — 배분액을 넘으면 요약만 앞부분만 남기고
     *      자를 수 있다(항상 유효한 범위의 substring만 사용해 예외 없이 안전하게).
     * 5) 담을 내용이 하나도 없는 영역은 헤더조차 출력하지 않는다.
     *
     * @param contextBudgetChars 이 문자열 전체가 넘지 않아야 하는 최대 길이(0 이상). 0이면
     *                           빈 문자열을 반환한다(예외 없음).
     */
    @Transactional(readOnly = true)
    public String buildContextBlock(Long conversationId, Long userId, String summary, int contextBudgetChars) {
        List<AiMessage> recent = aiMessageMapper.findRecentByConversationIdAndUserId(
                conversationId, userId, recentMessageLimit);
        List<UserContext> longTermContexts = userContextMapper.findActiveAndStaleByUserId(userId, longTermLimit);

        List<String> recentLines = renderLines(recent, this::renderRecentLine);
        List<String> longTermLines = renderLines(longTermContexts, this::renderLongTermLine);
        String recentHeader = recentHeader();
        boolean hasSummary = summary != null && !summary.isBlank();

        int recentNeed = recentLines.isEmpty() ? 0 : recentHeader.length() + totalLength(recentLines);
        int longTermNeed = longTermLines.isEmpty() ? 0 : LONG_TERM_HEADER.length() + totalLength(longTermLines);
        int summaryNeed = hasSummary ? SUMMARY_HEADER.length() + summary.length() : 0;

        ContextBudget budget = allocateBudget(contextBudgetChars, recentNeed, longTermNeed, summaryNeed);

        String summarySection = buildSummarySection(hasSummary ? summary : null, budget.summaryChars());
        String recentSection = buildSuffixSection(recentHeader, recentLines, budget.recentChars());
        String longTermSection = buildPrefixSection(LONG_TERM_HEADER, longTermLines, budget.longTermChars());

        StringBuilder sb = new StringBuilder();
        appendSection(sb, summarySection);
        appendSection(sb, recentSection);
        appendSection(sb, longTermSection);
        return sb.toString();
    }

    /** 각 영역의 "필요 길이"에 기본 비율을 상한으로 씌운 뒤, 남는 예산을 우선순위대로 재배분한다. */
    private ContextBudget allocateBudget(int contextBudgetChars, int recentNeed, int longTermNeed, int summaryNeed) {
        int total = Math.max(0, contextBudgetChars - SEPARATOR_RESERVE_CHARS);

        int recentBase = Math.min(recentNeed, (int) (total * RECENT_CONTEXT_RATIO));
        int longTermBase = Math.min(longTermNeed, (int) (total * LONG_TERM_CONTEXT_RATIO));
        int summaryBase = Math.min(summaryNeed, (int) (total * SUMMARY_CONTEXT_RATIO));

        int remaining = total - recentBase - longTermBase - summaryBase;

        int recentExtra = Math.min(remaining, Math.max(0, recentNeed - recentBase));
        int recentChars = recentBase + recentExtra;
        remaining -= recentExtra;

        int longTermExtra = Math.min(remaining, Math.max(0, longTermNeed - longTermBase));
        int longTermChars = longTermBase + longTermExtra;
        remaining -= longTermExtra;

        int summaryExtra = Math.min(remaining, Math.max(0, summaryNeed - summaryBase));
        int summaryChars = summaryBase + summaryExtra;

        return new ContextBudget(recentChars, longTermChars, summaryChars);
    }

    /**
     * 목록의 뒤(최신)에서부터 누적하며 배분액 안에 통째로 들어가는 항목만 남긴다 — 다음
     * 항목(더 오래된 것)을 더하면 넘치는 시점에서 멈춘다. 최근 대화 전용(원래 순서=오래된
     * -> 최신을 그대로 유지해야 하므로 뒤에서부터 고른 뒤 다시 원래 순서로 되돌린다).
     */
    private String buildSuffixSection(String header, List<String> lines, int allocatedChars) {
        if (lines.isEmpty()) {
            return "";
        }
        int pool = allocatedChars - header.length();
        if (pool < 0) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        int used = 0;
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i);
            if (used + line.length() > pool) {
                break;
            }
            kept.add(line);
            used += line.length();
        }
        if (kept.isEmpty()) {
            return "";
        }
        Collections.reverse(kept);
        StringBuilder sb = new StringBuilder(header);
        for (String line : kept) {
            sb.append(line);
        }
        return sb.toString();
    }

    /**
     * 목록의 앞(우선순위가 가장 높은 것 — 장기 컨텍스트는 최근 갱신 순)에서부터 누적하며
     * 배분액 안에 통째로 들어가는 항목만 남긴다. 다음 항목에서 넘치면 멈추고 그 뒤(더 오래된
     * 것들)는 전부 제외한다. 이미 우선순위 순서로 온 목록이라 순서를 되돌릴 필요가 없다.
     */
    private String buildPrefixSection(String header, List<String> lines, int allocatedChars) {
        if (lines.isEmpty()) {
            return "";
        }
        int pool = allocatedChars - header.length();
        if (pool < 0) {
            return "";
        }
        StringBuilder body = new StringBuilder();
        int used = 0;
        for (String line : lines) {
            if (used + line.length() > pool) {
                break;
            }
            body.append(line);
            used += line.length();
        }
        if (body.length() == 0) {
            return "";
        }
        return header + body;
    }

    /**
     * 요약은 세 영역 중 우선순위가 가장 낮다 — 전부 들어가면 그대로, 아니면 앞부분만 남기고
     * 자른다. substring 인자는 항상 0<=x<=summary.length() 범위 안에서만 계산해 예외를
     * 만들지 않는다. 자른 표시(SUMMARY_TRUNCATION_MARKER)조차 넣을 공간이 없으면 요약
     * 섹션 전체를 비운다 — 깨진 조각을 남기지 않는다.
     */
    private String buildSummarySection(String summary, int allocatedChars) {
        if (summary == null) {
            return "";
        }
        int fullLength = SUMMARY_HEADER.length() + summary.length();
        if (fullLength <= allocatedChars) {
            return SUMMARY_HEADER + summary;
        }

        int available = allocatedChars - SUMMARY_HEADER.length() - 1 - SUMMARY_TRUNCATION_MARKER.length();
        if (available <= 0) {
            return "";
        }
        String partial = summary.substring(0, Math.min(available, summary.length()));
        return SUMMARY_HEADER + partial + "\n" + SUMMARY_TRUNCATION_MARKER;
    }

    private void appendSection(StringBuilder sb, String section) {
        if (section.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append(section);
    }

    private String recentHeader() {
        return "[최근 대화 (최대 " + recentMessageLimit + "개)]\n";
    }

    private String renderRecentLine(AiMessage message) {
        String roleLabel = message.getRole() == MessageRole.USER ? "사용자" : "AI";
        return roleLabel + ": " + message.getContent() + "\n";
    }

    private String renderLongTermLine(UserContext context) {
        return "#" + context.getContextId() + " [" + context.getStatus() + "] " + context.getContent() + "\n";
    }

    private <T> List<String> renderLines(List<T> items, Function<T, String> renderer) {
        List<String> lines = new ArrayList<>(items.size());
        for (T item : items) {
            lines.add(renderer.apply(item));
        }
        return lines;
    }

    private int totalLength(List<String> lines) {
        int sum = 0;
        for (String line : lines) {
            sum += line.length();
        }
        return sum;
    }

    /** allocateBudget이 계산한, 각 영역에 실제로 쓸 수 있는 최대 문자 수. */
    private record ContextBudget(int recentChars, int longTermChars, int summaryChars) {
    }
}
