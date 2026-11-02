package com.jungwoo.project.memo.plan.dto;

/**
 * 항목의 시작 자료와 위치. {@link com.jungwoo.project.memo.plan.StartSourceResolver}가 인용 근거에서 만든다.
 *
 * @param page  PDF 파일에서 곧장 열 쪽(물리 페이지). PDF가 아니거나 파일이 바뀌었으면 null — 그때는 {@code locator}를 글로 보여 준다
 * @param state AVAILABLE(지금 파일과 같다) · CHANGED(파일이 바뀌었거나 구간이 새 분석으로 대체됐다) · DELETED(자료가 지워졌다)
 */
public record StartSource(
        Long materialId,
        Long sectionId,
        String filename,
        String contentType,
        String sectionTitle,
        String locator,
        String task,
        Integer page,
        String state
) {
}
