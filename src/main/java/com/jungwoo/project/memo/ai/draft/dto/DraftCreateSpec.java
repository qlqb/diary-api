package com.jungwoo.project.memo.ai.draft.dto;

import com.jungwoo.project.memo.ai.draft.DraftType;

/**
 * 새 draft 하나. 같은 턴의 draftOps에서 "new-{index}" 임시 id로 참조된다.
 *
 * @param sameGroupAs 있으면 그 draft의 draft_group_id를 물려받는다(숫자 id 또는 임시 id). 없으면
 *                    자기 id가 그룹 id다. 같은 create 배열 안에서는 index 순으로 "new-0"의 그룹에
 *                    붙일 수 있다
 */
public record DraftCreateSpec(
        DraftType draftType,
        String label,
        String sameGroupAs
) {
}
