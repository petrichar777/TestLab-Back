package com.exam.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "exam_paper")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExamPaper {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", unique = true, length = 64)
    private String code;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "description")
    private String description;

    @Column(name = "total_score", nullable = false)
    private Double totalScore = 0.0;

    @Column(name = "is_published", nullable = false)
    private Boolean isPublished = false;

    @Column(name = "visible_from")
    private LocalDateTime visibleFrom;

    @Column(name = "visible_to")
    private LocalDateTime visibleTo;

    @Column(name = "exam_duration_minutes")
    private Integer examDurationMinutes;

    @JsonIgnore
    @ManyToOne
    @JoinColumn(name = "created_by")
    private User createdBy;

    @Column(name = "imported_at")
    private LocalDateTime importedAt;

    @Column(name = "source_md_name")
    private String sourceMdName;

    @Column(name = "source_md_hash")
    private String sourceMdHash;

    @Column(name = "version", nullable = false)
    private Integer version = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "code_grading_mode")
    private CodeGradingMode codeGradingMode = CodeGradingMode.MANUAL;

    @Column(name = "ai_prompt_version")
    private Integer aiPromptVersion = 1;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
