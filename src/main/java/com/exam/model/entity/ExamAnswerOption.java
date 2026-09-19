package com.exam.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "exam_answer_option")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExamAnswerOption {
    @EmbeddedId
    @AttributeOverrides({
            @AttributeOverride(name = "attemptId", column = @Column(name = "attempt_id")),
            @AttributeOverride(name = "questionId", column = @Column(name = "question_id")),
            @AttributeOverride(name = "optionId", column = @Column(name = "option_id"))
    })
    private ExamAnswerOptionKey id;

    @ManyToOne(optional = false)
    @MapsId("attemptId")
    @JoinColumn(name = "attempt_id")
    private ExamAttempt attempt;

    @ManyToOne(optional = false)
    @MapsId("questionId")
    @JoinColumn(name = "question_id")
    private ExamQuestion question;

    @ManyToOne(optional = false)
    @MapsId("optionId")
    @JoinColumn(name = "option_id", insertable = false, updatable = false)
    private QuestionOption option;

    @Column(name = "selected_at", nullable = false)
    private LocalDateTime selectedAt;
}