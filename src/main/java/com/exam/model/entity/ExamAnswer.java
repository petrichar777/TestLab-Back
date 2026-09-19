package com.exam.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "exam_answer")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExamAnswer {
    @EmbeddedId
    @AttributeOverrides({
            @AttributeOverride(name = "attemptId", column = @Column(name = "attempt_id")),
            @AttributeOverride(name = "questionId", column = @Column(name = "question_id"))
    })
    private ExamAnswerKey id;

    @ManyToOne(optional = false)
    @MapsId("attemptId")
    @JoinColumn(name = "attempt_id", insertable = false, updatable = false)
    private ExamAttempt attempt;

    @ManyToOne(optional = false)
    @MapsId("questionId")
    @JoinColumn(name = "question_id", insertable = false, updatable = false)
    private ExamQuestion question;

    @Column(name = "awarded_score", nullable = false)
    private Double awardedScore = 0.0;

    @Column(name = "is_correct", nullable = false)
    private Boolean isCorrect = false;

    @Column(name = "answered_at", nullable = false)
    private LocalDateTime answeredAt;

    @Column(name = "ai_feedback")
    private String aiFeedback;
}
