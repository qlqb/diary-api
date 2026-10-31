package com.jungwoo.project.memo.material.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.material.domain.MaterialSection;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 실측에서 본 모델 출력 변형(코드 펜스, "S66" 접두어 id)을 값을 지어내지 않고 받아 준다. */
class ModelJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void codeFencesAndSurroundingProseAreStripped() {
        String raw = "여기 결과입니다.\n```json\n{\"ops\": []}\n```\n감사합니다";
        assertThat(ModelJson.unwrapObject(raw)).isEqualTo("{\"ops\": []}");
        assertThat(ModelJson.unwrapObject("no json here")).isNull();
        assertThat(ModelJson.unwrapObject(null)).isNull();
    }

    @Test
    void prefixedIdsAreReadAsNumbers_andNonNumbersBecomeNull() throws Exception {
        JsonNode node = mapper.readTree("{\"topicId\": \"#12\", \"sectionIds\": [\"S66\", 67, \"x\"], \"same\": null, \"bad\": \"abc\"}");
        assertThat(ModelJson.longOf(node, "topicId")).isEqualTo(12L);
        assertThat(ModelJson.longsOf(node, "sectionIds")).containsExactly(66L, 67L);
        assertThat(ModelJson.longOf(node, "same")).isNull();
        assertThat(ModelJson.longOf(node, "bad")).isNull();
        assertThat(ModelJson.longOf(node, "missing")).isNull();
    }

    @Test
    void submissionCandidates_needAnActionableRoleAndASubmissionWord() {
        MaterialSection schedule = MaterialSection.builder().rolesJson("[\"SCHEDULE\",\"ADMIN\"]")
                .assignmentQuote("중간평가 30%").assignmentCue(true).build();
        MaterialSection exercise = MaterialSection.builder().rolesJson("[\"EXERCISE\",\"ASSIGNMENT\"]")
                .assignmentQuote("실습 결과 화면과 코드를 제출하세요").assignmentCue(true).build();
        MaterialSection exerciseWithoutSubmit = MaterialSection.builder().rolesJson("[\"EXERCISE\"]")
                .assignmentQuote("연습 문제 3번").assignmentCue(true).build();

        assertThat(MaterialContentAnalyzer.looksLikeSubmission(schedule)).isFalse();
        assertThat(MaterialContentAnalyzer.looksLikeSubmission(exercise)).isTrue();
        assertThat(MaterialContentAnalyzer.looksLikeSubmission(exerciseWithoutSubmit)).isFalse();
    }
}
