package com.exam.repository;

import com.exam.model.entity.ExamPaper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;

public interface ExamPaperRepository extends JpaRepository<ExamPaper, Long> {
    Page<ExamPaper> findByIsPublishedTrue(Pageable pageable);
    List<ExamPaper> findByIsPublishedFalseAndVisibleFromNotNullAndVisibleFromBefore(LocalDateTime now);
}