package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.domain.FactKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 상담 기억의 서버 대조 규칙. 인용이 발화 안에 있어도 문장(text)은 모델이 쓴 것이다 — 숫자·단원·단정 여부를 서버가 대조한다.
 */
class StudyFactRulesTest {

    @Test
    void 문장의_숫자는_전부_사용자_발화에_있어야_하고_연도와_앞자리_0은_봐준다() {
        assertThat(StudyFactRules.numbersBackedBy("수업은 Unit 4까지 나갔다", "수업 Unit 4까지 나갔어")).isTrue();
        // 인용 "Unit 3" + 문장 "Unit 12까지" — 12는 사용자가 말하지 않았다.
        assertThat(StudyFactRules.numbersBackedBy("수업은 Unit 12까지 나갔다", "Unit 3이 어려워")).isFalse();
        // 날짜를 정규화하며 붙인 연도·앞자리 0
        assertThat(StudyFactRules.numbersBackedBy("시험은 2026-10-20이다", "시험 10월 20일이야")).isTrue();
        assertThat(StudyFactRules.numbersBackedBy("09시에 시작", "9시에 시작해")).isTrue();
    }

    @Test
    void 단원_번호는_번호가_단원_표기와_함께_나와야_가리킨_것으로_본다() {
        assertThat(StudyFactRules.unitNumberOf("Unit 10 What's your name?")).isEqualTo(10);
        assertThat(StudyFactRules.unitNumberOf("3장 스택")).isEqualTo(3);
        assertThat(StudyFactRules.unitNumberOf("What's your name?")).isNull();

        // 제목이 같아도 번호로 가른다 — "Unit 1"은 Unit 10이 아니다.
        assertThat(StudyFactRules.mentionsTopic("Unit 1 이름 묻기가 헷갈려", "Unit 10 What's your name?", 10)).isFalse();
        assertThat(StudyFactRules.mentionsTopic("Unit 1 이름 묻기가 헷갈려", "Unit 1 What's your name?", 1)).isTrue();
        assertThat(StudyFactRules.mentionsTopic("3과에서 막혔어", "Lesson 3 Travel", null)).isTrue();
        assertThat(StudyFactRules.mentionsTopic("과목이 3개야", "Lesson 3 Travel", null)).isFalse();
        // 번호 없는 제목은 핵심어로, 번호도 핵심어도 없으면 가리키지 않은 것이다.
        assertThat(StudyFactRules.mentionsTopic("homework 문장이 어려워", "I'm doing my homework right now", null)).isTrue();
        assertThat(StudyFactRules.mentionsTopic("설명 보고 이제 만들 수 있어", "Unit 3 I have to make reservations", 3))
                .isFalse();
    }

    @Test
    void 진도를_자동으로_바꾸려면_인용이_그_주장을_직접_단정해야_한다() {
        assertThat(StudyFactRules.assertsCurrentState("수업은 Unit 4까지 나갔다", "Unit 4까지 나갔어")).isTrue();
        // 같은 숫자의 부정·미완·질문·가정
        assertThat(StudyFactRules.assertsCurrentState("Unit 3까지 수업 완료", "Unit 3은 아직 못 끝냈어")).isFalse();
        assertThat(StudyFactRules.assertsCurrentState("Unit 5까지 나감", "Unit 5까지 나가면 좋겠다")).isFalse();
        assertThat(StudyFactRules.assertsCurrentState("Unit 4까지 했다", "Unit 4까지 했나?")).isFalse();
        // 숫자가 인용 밖에 있으면(발화의 다른 곳) 이 주장의 근거가 아니다.
        assertThat(StudyFactRules.assertsCurrentState("Unit 4까지 나갔다", "나갔어")).isFalse();
        assertThat(StudyFactRules.assertsCurrentState("Unit 4까지 나갔다", null)).isFalse();
        // 붙여 쓴 가정문
        assertThat(StudyFactRules.assertsCurrentState("Unit 5까지 나감", "Unit 5까지 나가면좋겠다")).isFalse();
        assertThat(StudyFactRules.assertsCurrentState("Unit 5까지 나감", "Unit 5까지 나간 것 같아")).isFalse();
        assertThat(StudyFactRules.assertsCurrentState("Unit 5까지 나감", "Unit 5까지 나가면시험 공부 시작할게")).isFalse();
        // 진도와 막힘을 한 문장에 말해도 진도는 단정이다.
        assertThat(StudyFactRules.assertsCurrentState("수업은 Unit 4까지 나갔다", "Unit 4까지 나갔고 Unit 3이 막혀")).isTrue();
    }

    @Test
    void 진도_시험_막힘_해결은_인용에_그_종류의_단서가_있어야_한다() {
        assertThat(StudyFactRules.kindBackedBy(FactKind.PROGRESS, "Unit 3이 어려워"))
                .isFalse();
        assertThat(StudyFactRules.kindBackedBy(FactKind.PROGRESS, "Unit 4까지 나갔어"))
                .isTrue();
        assertThat(StudyFactRules.kindBackedBy(FactKind.DIFFICULTY, "Unit 3이 어려워"))
                .isTrue();
        assertThat(StudyFactRules.kindBackedBy(FactKind.RESOLVED, "설명 보고 이제 만들 수 있어"))
                .isTrue();
        assertThat(StudyFactRules.kindBackedBy(FactKind.RESOLVED, "Unit 3 막혀")).isFalse();
        assertThat(StudyFactRules.kindBackedBy(FactKind.RESOLVED, "아직 해결 못 했어")).isFalse();
        assertThat(StudyFactRules.kindBackedBy(FactKind.RESOLVED, "설명 봤는데 이제 더 헷갈려")).isFalse();
        assertThat(StudyFactRules.kindBackedBy(FactKind.RESOLVED, "해결했어")).isTrue();
        assertThat(StudyFactRules.kindBackedBy(FactKind.PREFERENCE, "아무 말")).isTrue();
    }

    @Test
    void 진도_막힘_해결_신호가_분명한_발화만_보조_추출을_부른다() {
        assertThat(StudyFactRules.strongStudySignal("수업은 Unit 4까지 나갔어")).isTrue();
        assertThat(StudyFactRules.strongStudySignal("방금 설명 보고 이제 have to 문장 만들 수 있어")).isTrue();
        assertThat(StudyFactRules.strongStudySignal("고마워, 내일 또 하자")).isFalse();
    }
}
