package com.exam.repository;

import com.exam.model.entity.ExamAnswerOption;
import com.exam.model.entity.ExamAnswerOptionKey;
import com.exam.model.entity.ExamAttempt;
import com.exam.model.entity.ExamQuestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExamAnswerOptionRepository extends JpaRepository<ExamAnswerOption, ExamAnswerOptionKey> {
    List<ExamAnswerOption> findByAttemptAndQuestion(ExamAttempt attempt, ExamQuestion question);

    /** 一次取回整个 attempt 的作答选项（替代循环内 findByAttemptAndQuestion，消除 N+1） */
    List<ExamAnswerOption> findByAttempt(ExamAttempt attempt);
    void deleteByAttempt(ExamAttempt attempt);
}
