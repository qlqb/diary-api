package com.jungwoo.project.memo.ai.dto;

import java.util.List;

/** ChatClient 구조화 출력의 최상위 응답. 배열을 직접 쓰지 않고 객체로 감싼다. */
public record TodayProposal(List<ProposalItem> items) {
}
