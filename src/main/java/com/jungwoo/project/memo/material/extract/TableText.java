package com.jungwoo.project.memo.material.extract;

import java.util.List;

/**
 * 표를 텍스트로 옮기는 방식. HWP와 HWPX가 같은 모양을 쓴다.
 *
 * <p>셀을 공백으로 이어 붙이면 강의계획서의 주차표가 "3주차 연결 리스트 9/25 5주차 스택 10/9"처럼
 * 한 줄로 뭉개져, 어느 날짜가 어느 주차의 것인지 모델도 사람도 알 수 없다. 행은 줄로, 셀은 |로
 * 나누고 표의 시작과 끝을 표시해 관계가 남게 한다.
 */
final class TableText {

    private TableText() {
    }

    static String render(List<List<String>> rows) {
        if (rows.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("[표 시작]\n");
        for (List<String> row : rows) {
            sb.append("| ");
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) {
                    sb.append(" | ");
                }
                // 셀 안의 줄바꿈은 칸을 깨뜨리므로 공백으로 눕힌다. 셀 경계는 |가 지킨다.
                sb.append(row.get(i) == null ? "" : row.get(i).replace('\n', ' ').replace('\r', ' ').trim());
            }
            sb.append(" |\n");
        }
        return sb.append("[표 끝]").toString();
    }
}
