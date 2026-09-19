package com.exam.repository;

import com.exam.model.entity.ExamAnswer;
import com.exam.model.entity.ExamAnswerKey;
import com.exam.model.entity.ExamAttempt;
import com.exam.model.entity.ExamQuestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExamAnswerRepository extends JpaRepository<ExamAnswer, ExamAnswerKey> {
    List<ExamAnswer> findByAttempt(ExamAttempt attempt);
    List<ExamAnswer> findByQuestion(ExamQuestion question);
    void deleteByAttempt(ExamAttempt attempt);
}
