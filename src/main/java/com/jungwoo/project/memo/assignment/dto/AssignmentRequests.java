package com.jungwoo.project.memo.assignment.dto;

import com.jungwoo.project.memo.assignment.domain.AssignmentConfirmStatus;
import com.jungwoo.project.memo.assignment.domain.DueKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 과제 화면의 작은 요청들. 각각이 "무엇을 확정하는지"가 드러나게 나눴다. */
public final class AssignmentRequests {

    private AssignmentRequests() {
    }

    /** 「과제 맞아요 / 연습용이에요 / 나중에 / 같은 과제예요」. 마감은 여기서 확정하지 않는다. */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class Answer {
        @NotNull
        private AssignmentConfirmStatus answer;
        /** answer가 DUPLICATE일 때 원본. */
        private Long duplicateOfAssignmentId;
        @NotNull
        private Long version;
    }

    /**
     * 마감. dueKind가 DATE면 dueDate, DATETIME이면 dueAt(사용자 시간대의 로컬 시각), NONE/UNKNOWN이면 둘 다 null.
     * 추정 후보를 그대로 확정할 때도 화면이 그 값을 여기 실어 보낸다 — "이 날짜 맞아요"는 이 요청이다.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class Due {
        @NotNull
        private DueKind dueKind;
        private LocalDate dueDate;
        private LocalDateTime dueAt;
        @NotNull
        private Long version;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Rename {
        @NotBlank
        @Size(max = 300)
        private String title;
        @NotNull
        private Long version;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Complete {
        @NotNull
        private Boolean completed;
        @NotNull
        private Long version;
    }

    /** 직접 추가. 자료 없이도 과제는 있을 수 있다. */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class Create {
        private Long courseId;
        @NotBlank
        @Size(max = 300)
        private String title;
        private DueKind dueKind;
        private LocalDate dueDate;
        private LocalDateTime dueAt;
    }
}
