package com.jungwoo.project.memo.material.batch.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/** 업로드 묶음 요청. */
public final class BatchRequests {

    private BatchRequests() {
    }

    /**
     * 고른 파일 목록으로 묶음을 연다. 아직 아무것도 올리지 않은 상태다.
     *
     * <p>서버가 자리(item)를 만들어 돌려주고, 업로드는 자리마다 하나씩 들어온다. 구성원을
     * 여기서 고정하는 이유: 분석 도중 파일이 더 들어와 분모가 바뀌면 진행률이 뒤로 간다.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class Create {
        /** 프로젝트 화면에서 시작했으면 그 프로젝트. 자료함이면 null. */
        private Long courseId;
        @NotEmpty
        @Size(max = 50)
        private List<File> files;

        @Getter
        @Setter
        @NoArgsConstructor
        public static class File {
            @NotNull
            @Size(max = 255)
            private String filename;
            private Long sizeBytes;
        }
    }

    /** 미리보기만. 묶음을 만들지 않고 예상 시간만 본다. */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class Estimate {
        @NotEmpty
        @Size(max = 50)
        private List<Create.File> files;
    }
}
