package com.jungwoo.project.memo.learning.structure;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/**
 * 변경안 적용. selectedOpIndexes가 null이면 전부. titleOverrides는 ops 인덱스 → 사용자가 고친 제목
 * (ADD/RENAME에만 적용). 선택에서 뺀 작업은 적용되지 않을 뿐 기록은 남는다.
 */
@Getter
@Setter
@NoArgsConstructor
public class TopicChangeApplyRequest {
    private List<Integer> selectedOpIndexes;
    private Map<Integer, String> titleOverrides;
}
