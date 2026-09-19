package com.exam.repository;

import com.exam.model.entity.ExamAnswerText;
import com.exam.model.entity.ExamAnswerTextKey;
import com.exam.model.entity.ExamAttempt;
import com.exam.model.entity.ExamQuestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExamAnswerTextRepository extends JpaRepository<ExamAnswerText, ExamAnswerTextKey> {
    List<ExamAnswerText> findByAttemptAndQuestion(ExamAttempt attempt, ExamQuestion question);
    List<ExamAnswerText> findByAttempt(ExamAttempt attempt);
    void deleteByAttempt(ExamAttempt attempt);
}
