package com.jungwoo.project.memo.plan.provenance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 제공한 원본 한 줄이 <b>어느 자료 파일에서 왔는가</b>. 서버가 원문 탐색을 위해 붙이는
 * 메타데이터이고, 모델에 준 값이 아니다(그쪽은 {@link ProvidedSource#providedValue()}).
 *
 * <p>학습 항목(TOPIC)은 자료 분석을 확정할 때 생기므로 course_topics가 원본 자료 id와
 * 위치(source_locator)를 이미 갖고 있다. 여기서는 그 연결을 생성 시점에 그대로 보존한다 —
 * 나중에 자료가 교체·삭제되거나 항목이 다른 자료로 다시 연결돼도 "그때는 이 파일이었다"를
 * 말할 수 있어야 한다.
 *
 * <p>★ 모델은 학습 항목의 제목과 위치 문자열만 받았다. 파일 자체를 읽은 것이 아니다.
 * 화면은 이 값을 "이 학습 항목의 원본 자료"로 말하지 "AI가 읽은 문서"로 말하지 않는다.
 *
 * <p>schema_version 2부터 있다. 1판 JSON에는 이 필드가 없고 null로 읽힌다.
 *
 * @param materialId  course_materials.material_id
 * @param filename    생성 당시의 원본 파일명
 * @param contentType 생성 당시의 MIME. 화면이 "열기"와 "내려받기"를 가르는 데 쓴다
 * @param fileHash    생성 당시 파일의 SHA-256. 예전 업로드는 null일 수 있다. 지금 파일과
 *                    다르면 "원본이 변경됨"이다 — 과거 파일을 복원할 수 있다는 뜻은 아니다
 * @param locator     자료 안의 위치 문자열("2주차"). 사람이 읽는 값이고, 페이지 번호가 아니다.
 *                    "2주차"를 PDF 2쪽으로 해석하지 않는다
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProvidedMaterial(
        Long materialId,
        String filename,
        String contentType,
        String fileHash,
        String locator
) {
}
