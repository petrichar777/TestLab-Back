package com.exam.model.dto.request;

import lombok.Data;

import java.util.List;

@Data
public class ExamSubmitRequest {
    private Long attemptId;
    private List<AnswerItem> answers;

    @Data
    public static class AnswerItem {
        private Long questionId;
        private List<Long> optionIds;
        private List<String> textAnswers;
    }
}