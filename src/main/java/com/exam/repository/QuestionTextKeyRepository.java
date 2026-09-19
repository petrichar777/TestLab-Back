package com.exam.repository;

import com.exam.model.entity.ExamQuestion;
import com.exam.model.entity.KeyType;
import com.exam.model.entity.QuestionTextKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface QuestionTextKeyRepository extends JpaRepository<QuestionTextKey, Long> {
    List<QuestionTextKey> findByQuestionAndKeyTypeOrderByKeyIndexAsc(ExamQuestion question, KeyType keyType);
    List<QuestionTextKey> findByQuestion(ExamQuestion question);

    /** 批量查询多个题目的指定类型参考答案（替代循环内 findByQuestionAndKeyTypeOrderByKeyIndexAsc，消除 N+1） */
    List<QuestionTextKey> findByQuestionIdInAndKeyType(Collection<Long> questionIds, KeyType keyType);
    void deleteByQuestion(ExamQuestion question);
    void deleteByQuestionIn(java.util.List<ExamQuestion> questions);
    
}
