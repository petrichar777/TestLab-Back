package com.exam.model.entity;

import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Embeddable
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExamAnswerOptionKey implements Serializable {
    private Long attemptId;
    private Long questionId;
    private Long optionId;
}