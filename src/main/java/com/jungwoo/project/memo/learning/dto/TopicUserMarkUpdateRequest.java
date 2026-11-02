package com.jungwoo.project.memo.learning.dto;

import com.jungwoo.project.memo.learning.domain.TopicUserMark;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 「이미 알아요」/「나중에」와 그 해제.
 *
 * <p>mark에 @NotNull을 걸지 않는다 — null이 유효한 값이고 "표식을 지운다"는 뜻이다.
 * 되돌릴 수 없는 표시를 만들지 않기 위해 해제를 같은 엔드포인트로 받는다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TopicUserMarkUpdateRequest {

    private TopicUserMark mark;
}
