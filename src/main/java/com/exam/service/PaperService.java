package com.exam.service;

import com.exam.exception.BusinessException;
import com.exam.model.entity.ExamPaper;
import com.exam.model.entity.ExamQuestion;
import com.exam.model.entity.QuestionOption;
import com.exam.repository.ExamPaperRepository;
import com.exam.repository.ExamQuestionRepository;
import com.exam.repository.QuestionOptionRepository;
import com.exam.security.SecurityUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PaperService {
    private final ExamPaperRepository paperRepo;
    private final ExamQuestionRepository questionRepo;
    private final QuestionOptionRepository optionRepo;

    public PaperService(ExamPaperRepository paperRepo, ExamQuestionRepository questionRepo, QuestionOptionRepository optionRepo) {
        this.paperRepo = paperRepo;
        this.questionRepo = questionRepo;
        this.optionRepo = optionRepo;
    }

    public Page<ExamPaper> listPublished(Pageable pageable) {
        return paperRepo.findByIsPublishedTrue(pageable);
    }

    /**
     * 试卷详情（考前公开接口，不暴露答案）：
     * 返回结构保持 [paper, questions, options] 三元素数组；
     * - paper 为 ExamPaper 实体（createdBy 已 @JsonIgnore，不泄露创建者信息）；
     * - questions 仅含 id/type/content/score/orderIndex，不嵌套 paper；
     * - options 仅含 id/questionId/label/content，去掉 isCorrect 正确答案标记。
     */
    public List<Object> detail(Long id) {
        ExamPaper paper = paperRepo.findById(id).orElseThrow(() -> new BusinessException(404, "exam paper not found"));
        // 未发布/定时发布试卷不对外暴露：匿名与普通用户一律 404（与资源不存在同响应，防止枚举）；
        // 仅 ADMIN 可查看（用于管理端预览）。GET /api/papers/{id} 仍保持匿名可达（公开已发布试卷）。
        if (!Boolean.TRUE.equals(paper.getIsPublished()) && !SecurityUtils.hasRole("ADMIN")) {
            throw new BusinessException(404, "exam paper not found");
        }
        List<ExamQuestion> questions = questionRepo.findByPaperOrderByOrderIndexAsc(paper);

        List<Map<String, Object>> questionItems = new ArrayList<>();
        for (ExamQuestion q : questions) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", q.getId());
            item.put("type", q.getType());
            item.put("content", q.getContent());
            item.put("score", q.getScore());
            item.put("orderIndex", q.getOrderIndex());
            questionItems.add(item);
        }

        List<Map<String, Object>> optionItems = new ArrayList<>();
        for (ExamQuestion q : questions) {
            for (QuestionOption opt : optionRepo.findByQuestion(q)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", opt.getId());
                item.put("questionId", q.getId());
                item.put("label", opt.getLabel());
                item.put("content", opt.getContent());
                optionItems.add(item);
            }
        }

        List<Object> resp = new ArrayList<>();
        resp.add(paper);
        resp.add(questionItems);
        resp.add(optionItems);
        return resp;
    }
}