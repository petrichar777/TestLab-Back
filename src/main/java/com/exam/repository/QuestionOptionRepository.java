package com.exam.repository;

import com.exam.model.entity.ExamQuestion;
import com.exam.model.entity.QuestionOption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface QuestionOptionRepository extends JpaRepository<QuestionOption, Long> {
    List<QuestionOption> findByQuestion(ExamQuestion question);
    List<QuestionOption> findByQuestionAndIsCorrectTrue(ExamQuestion question);

    /** 批量查询多个题目的全部选项（替代循环内 findByQuestion，消除 N+1） */
    List<QuestionOption> findByQuestionIdIn(Collection<Long> questionIds);

    /** 批量查询多个题目的正确选项（替代循环内 findByQuestionAndIsCorrectTrue） */
    List<QuestionOption> findByQuestionIdInAndIsCorrectTrue(Collection<Long> questionIds);
}