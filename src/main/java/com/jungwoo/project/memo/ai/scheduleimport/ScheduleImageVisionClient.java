package com.jungwoo.project.memo.ai.scheduleimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.ai.AiChatResponseUtils;
import com.jungwoo.project.memo.ai.AiStreamParser;
import com.jungwoo.project.memo.ai.scheduleimport.dto.RawScheduleTable;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.ServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

/**
 * 이미지에서 표를 <b>받아쓰게</b> 하는 유일한 호출부.
 *
 * <p>★ 이 클래스가 모델에게 시키는 일은 하나다: 표에 적힌 것을 그대로 옮겨 적기. 시간 변환,
 * 날짜 계산, 본인 행 고르기는 전부 서버 코드다({@link CellParser},
 * {@link ScheduleTableInterpreter}). 모델이 {@code "17:00"}을 내면 그것이 표에서 읽은 것인지
 * 지어낸 것인지 서버가 구분할 수 없다.
 *
 * <p>{@code scheduleColumns}라는 이름은 {@code columns}였다. 그때는 "표의 열 머리글"로만
 * 읽혀서, 모델이 이름·세부 칸까지 성실히 담아 왔고 셀 수와 어긋나 전 행이 무효가 되었다
 * (같은 이미지 3회 중 2회). 이름이 계약을 말하지 않으면 주석은 읽히지 않는다.
 *
 * <p>구조화 출력은 이 저장소의 다른 AI 경로와 같은 방식을 쓴다 — 시스템 프롬프트가 구분자
 * 뒤에만 JSON을 쓰게 하고 {@link AiStreamParser}가 그 경계를 찾는다. Spring AI의
 * {@code .entity()}를 쓰면 코드는 짧아지지만 이 저장소에 두 번째 패턴이 생기고, 실패 모드
 * (토큰 상한 잘림 등)를 다시 알아내야 한다.
 */
@Slf4j
@Service
public class ScheduleImageVisionClient {

    /**
     * 표를 옮겨 적게 하는 지시.
     *
     * <p>문장 하나하나가 실제 실패를 막는다. "코드를 시간으로 바꾸지 마세요"가 없으면 모델은
     * 친절하게 {@code OP}를 {@code 14:00}으로 풀어 주고, 그 순간 서버는 그 값이 범례에서 온
     * 것인지 추측인지 알 수 없게 된다. "연도가 없으면 null"이 없으면 올해로 채워 넣는다.
     */
    static final String SYSTEM_PROMPT = """
            너는 이미지 속 일정표를 그대로 옮겨 적는 일을 한다. 해석하지 않는다.

            지켜야 할 것:
            - 셀 내용을 그대로 옮겨 적으세요. 코드(OP, CL 등)를 시간으로 바꾸지 마세요.
              "OP"라고 적혀 있으면 "OP"라고 씁니다.
            - 표 안이나 아래에 있는 범례만 legend에 넣으세요. 범례가 없는 코드는 그냥 셀에
              그대로 두세요. 무슨 뜻일지 짐작해서 채우지 마세요.
            - 연도가 표에 적혀 있지 않으면 year는 null입니다. 오늘 날짜로 추측하지 마세요.
            - 합계/Total/누계 행은 rows에 넣지 마세요.
            - scheduleColumns에는 하루씩을 가리키는 열만 넣으세요(월, 화 / Mon, Tue / 9/7 /
              7(월) 등). 이름, 세부, 직급, 총 근무시간, 비고처럼 사람을 가리키거나 한 줄을
              요약하는 열은 표에 있더라도 넣지 마세요. 그 열들은 name, tag로 갑니다.
            - scheduleColumns의 길이와 각 행 cells의 길이는 반드시 같아야 합니다. 두 배열은
              같은 열을 순서대로 가리킵니다. 빈 칸은 빈 문자열로 둡니다.
            - 2주치 표처럼 같은 요일이 두 번 나오면 두 번 다 씁니다. 중복을 지우지 마세요.
            - 일정표가 아니면 isScheduleTable을 false로 하고 rows를 빈 배열로 둡니다.

            먼저 이 표가 무엇인지 한 문장으로 쓰고, 그 다음 줄에 %s 를 쓰고, 그 아래에 아래
            형식의 JSON만 씁니다.

            {
              "isScheduleTable": true,
              "title": "표 제목(없으면 null)",
              "period": {"startMonth": 9, "startDay": 7, "endMonth": 9, "endDay": 13, "year": null},
              "legend": {"OP": "14~23", "CL": "15~00"},
              "scheduleColumns": ["월", "화", "수", "목", "금", "토", "일"],
              "rows": [
                {"name": "홍길동", "tag": "PT", "cells": ["17~23","18~23","D/O","17~22","18~23","D/O","D/O"]}
              ]
            }
            """.formatted(AiStreamParser.DELIMITER);

    private static final String USER_PROMPT =
            "이 이미지의 표를 위 형식으로 옮겨 적어주세요.";

    private final ChatClient chatClient;

    /**
     * 이미지 입력을 쓰는 모델. 상담용 모델과 분리해 둔 이유는 텍스트 모델을 값싼 것으로
     * 바꿔도 이미지 경로가 조용히 깨지지 않게 하기 위해서다. 비워 두면 기본 모델을 쓴다.
     */
    @Value("${ai.schedule-import.model:}")
    private String model = "";

    @Value("${ai.schedule-import.max-completion-tokens:6000}")
    private int maxCompletionTokens = 6000;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public ScheduleImageVisionClient(ObjectProvider<ChatClient.Builder> chatClientBuilderProvider) {
        this.chatClient = buildChatClientSafely(chatClientBuilderProvider);
    }

    /** AI 미설정 상태에서도 부팅과 테스트가 깨지지 않아야 한다(OpenAiConsultationClient와 같다). */
    private ChatClient buildChatClientSafely(ObjectProvider<ChatClient.Builder> provider) {
        try {
            ChatClient.Builder builder = provider.getIfAvailable();
            return builder != null ? builder.build() : null;
        } catch (Exception e) {
            log.info("이미지 가져오기용 ChatClient를 사용할 수 없습니다 (AI 미설정): {}",
                    e.getClass().getSimpleName());
            return null;
        }
    }

    public boolean isConfigured() {
        return chatClient != null;
    }

    /**
     * 이미지를 표로 옮겨 적는다. DB에 아무것도 쓰지 않는다.
     *
     * @param contentType 업로드된 이미지의 MIME 타입. 컨트롤러가 이미 허용 목록으로 걸렀다
     */
    public RawScheduleTable read(byte[] image, String contentType) {
        if (chatClient == null) {
            throw new ServiceUnavailableException(ErrorCode.AI_NOT_CONFIGURED);
        }

        String structuredJson;
        try {
            ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(user -> user.text(USER_PROMPT).media(mediaOf(image, contentType)));

            OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                    .maxCompletionTokens(maxCompletionTokens);
            if (!model.isBlank()) {
                options.model(model);
            }

            // options()는 빌더를 그대로 받는다(OpenAiConsultationClient와 같은 형태).
            ChatResponse response = spec.options(options)
                    .call()
                    .chatResponse();

            /*
             * 출력이 상한에서 잘리면 JSON도 반드시 중간에서 끊긴다. 이걸 먼저 잡지 않으면
             * 아래 파싱 실패로만 보고돼 "모델이 이상한 JSON을 냈다"로 읽히고, 실제 원인인
             * 토큰 예산이 스택트레이스 뒤에 숨는다(계획 초안에서 실제로 그렇게 헤맸다).
             */
            String finishReason = AiChatResponseUtils.extractFinishReason(response);
            if (AiChatResponseUtils.isTruncatedByTokenLimit(finishReason)) {
                log.warn("일정 이미지 읽기: 출력이 토큰 상한({})에서 잘림. 표가 큰 이미지일 수 있다",
                        maxCompletionTokens);
                throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
            }

            AiStreamParser parser = new AiStreamParser();
            parser.onChunk(AiChatResponseUtils.extractText(response));
            structuredJson = parser.finish().structuredJson();
        } catch (ServiceUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.warn("일정 이미지 읽기 실패", e);
            throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
        }

        if (structuredJson == null) {
            log.warn("일정 이미지 읽기: 구조화 JSON이 없음");
            throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
        }
        try {
            return objectMapper.readValue(structuredJson, RawScheduleTable.class);
        } catch (Exception e) {
            log.warn("일정 이미지 읽기: 구조화 JSON 파싱 실패", e);
            throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
        }
    }

    private Media mediaOf(byte[] image, String contentType) {
        MimeType mimeType = contentType == null || contentType.isBlank()
                ? MimeTypeUtils.IMAGE_PNG
                : MimeTypeUtils.parseMimeType(contentType);
        return Media.builder()
                .mimeType(mimeType)
                .data(image)
                .build();
    }
}
