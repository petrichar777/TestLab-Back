package com.exam.model.dto.request;

import lombok.Data;

import java.util.List;

@Data
public class AdminCodeGradeRequest {
    private Long attemptId;
    private List<GradeItem> grades;

    @Data
    public static class GradeItem {
        private Long questionId;
        private Double awardedScore;
    }
}