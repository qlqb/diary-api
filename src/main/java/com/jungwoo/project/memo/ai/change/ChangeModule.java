package com.jungwoo.project.memo.ai.change;

import java.util.List;
import java.util.Map;

/**
 * 데이터를 소유하는 도메인이 상담에 내놓는 변경 기능. 업무 규칙은 그 도메인의 기존 서비스에 있고, 이 모듈은 그것을 감싼다 — 확인(읽기),
 * 잠금, 최신 재확인, 적용. 공통 처리부(묶음·적용)는 op 타입을 몰라도 모듈을 통해 처리한다. 새 도메인은 모듈 빈 하나와 op 타입을 더한다.
 *
 * <p>모든 메서드는 서버 실행 문맥의 userId로만 동작한다(모델 인자에 사용자 id는 없다). 중첩 id(예외↔반복 일정, 과제↔과목)도 소유를 확인한다.
 */
public interface ChangeModule {

    /** 이 모듈이 다루는 op 이름 → 타입(저장된 op를 읽을 때 쓴다). */
    Map<String, Class<? extends ChangeOp>> opTypes();

    /** 적용 때 잠금 순서. 기존 경로와 맞춘다: 약속(10) → 실행(20) → 반복 일정(30) → 과제(40). */
    int lockOrder();

    /** 읽기 확인. 통과하면 검토 당시 대상 상태를 채운 op를 돌려준다. DB를 바꾸지 않는다. */
    ChangeCheck check(Long userId, ChangeOp op);

    /** 적용 트랜잭션 안에서 이 모듈의 대상을 잠근다(같은 순서로). */
    void lock(Long userId, List<ChangeOp> ops);

    /** 잠근 뒤 대상이 검토 당시와 같은지 다시 본다. 다르면 STALE. */
    ChangeCheck recheck(Long userId, ChangeOp op);

    /** 기존 서비스로 적용한다. 만든 것의 id 등 결과를 돌려준다(묶음에 남는다). 같은 트랜잭션이다. */
    Map<String, Object> apply(Long userId, ChangeOp op);

    /** 이 op와 함께 고를 만한 관련 변경(근무를 빼면 그 근무의 이동시간 등). 기본은 없다. */
    default List<LinkedChange> linked(Long userId, ChangeOp op) {
        return List.of();
    }

    /**
     * @param stored 저장된 연결(약속의 파생 이동)이면 true — 기본 선택. 제목·시각으로 추정한 관계면 false — 기본 미선택
     * @param reason 화면에 보일 이유
     */
    record LinkedChange(ChangeOp op, boolean stored, String reason) {
    }
}
