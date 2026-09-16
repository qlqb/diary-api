package com.jungwoo.project.memo.plan.selection;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 호출 한 번의 입력 토큰 추정. 예산은 이 값으로 관리한다(글자 수·줄 수로 자르지 않는다).
 *
 * <p>인코딩은 o200k_base다 — gpt-4o 이후 OpenAI 모델 계열의 토크나이저이고 Spring AI가 이미 의존하는 jtokkit이 제공한다.
 * 운영 모델(gpt-5.6-luna)의 토크나이저가 공개돼 있지 않으므로 이것은 <b>근사</b>다. 그래서:
 * <ul>
 *   <li>메시지 틀(role·구분자)의 고정 오버헤드를 더하고,</li>
 *   <li>안전 여유(기본 10%)를 곱하며,</li>
 *   <li>실호출의 ai_usage_logs.input_tokens와 대조해 오차를 기록한다(15번 문서 §7.4).</li>
 * </ul>
 * 한국어 한 번의 글자/토큰 비율을 보편값으로 쓰지 않는다 — 문장마다 비율이 다르다.
 */
@Component
public class PromptTokenEstimator {

    /** 시스템·사용자 두 메시지의 틀. OpenAI 채팅 형식에서 메시지당 몇 토큰이다. */
    static final int MESSAGE_OVERHEAD_TOKENS = 12;

    /** 어휘표가 커서(수십 MB) 인스턴스마다 만들지 않는다 — 처음 쓸 때 한 번만 읽는다. */
    private static final class Holder {
        static final Encoding ENCODING = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.O200K_BASE);
    }

    @Value("${plan.selection.token-safety-margin:0.10}")
    private double safetyMargin = 0.10;

    public PromptTokenEstimator() {
    }

    PromptTokenEstimator(double safetyMargin) {
        this.safetyMargin = safetyMargin;
    }

    /** 문자열 하나의 토큰 수(여유 없음). */
    public int count(String text) {
        return text == null || text.isEmpty() ? 0 : Holder.ENCODING.countTokensOrdinary(text);
    }

    /** 호출 한 번(시스템 + 사용자 프롬프트)의 입력 토큰 추정. 여유를 곱한 값이다. */
    public int estimateCall(String systemPrompt, String userPrompt) {
        int raw = count(systemPrompt) + count(userPrompt) + MESSAGE_OVERHEAD_TOKENS;
        return (int) Math.ceil(raw * (1.0 + safetyMargin));
    }

    /** 여유를 곱하지 않은 값. 실측과 대조할 때 쓴다. */
    public int rawCall(String systemPrompt, String userPrompt) {
        return count(systemPrompt) + count(userPrompt) + MESSAGE_OVERHEAD_TOKENS;
    }

    public double safetyMargin() {
        return safetyMargin;
    }
}
