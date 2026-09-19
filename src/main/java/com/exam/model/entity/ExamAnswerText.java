package com.exam.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "exam_answer_text")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExamAnswerText {
    @EmbeddedId
    @AttributeOverrides({
            @AttributeOverride(name = "attemptId", column = @Column(name = "attempt_id")),
            @AttributeOverride(name = "questionId", column = @Column(name = "question_id")),
            @AttributeOverride(name = "textIndex", column = @Column(name = "text_index"))
    })
    private ExamAnswerTextKey id;

    @ManyToOne(optional = false)
    @MapsId("attemptId")
    @JoinColumn(name = "attempt_id", insertable = false, updatable = false)
    private ExamAttempt attempt;

    @ManyToOne(optional = false)
    @MapsId("questionId")
    @JoinColumn(name = "question_id", insertable = false, updatable = false)
    private ExamQuestion question;

    @Column(name = "text_value", nullable = false)
    private String textValue;

    @Column(name = "answered_at", nullable = false)
    private LocalDateTime answeredAt;
}