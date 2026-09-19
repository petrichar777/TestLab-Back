package com.exam.repository;

import com.exam.model.entity.ExamQuestion;
import com.exam.model.entity.ExamPaper;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExamQuestionRepository extends JpaRepository<ExamQuestion, Long> {
    List<ExamQuestion> findByPaperOrderByOrderIndexAsc(ExamPaper paper);
}