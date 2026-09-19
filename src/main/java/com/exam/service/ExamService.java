package com.exam.service;

import com.exam.exception.BusinessException;
import com.exam.model.dto.request.ExamSubmitRequest;
import com.exam.model.entity.*;
import com.exam.repository.*;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class ExamService {
    private final ExamPaperRepository paperRepo;
    private final UserRepository userRepo;
    private final ExamQuestionRepository questionRepo;
    private final QuestionOptionRepository optionRepo;
    private final ExamAttemptRepository attemptRepo;
    private final ExamAnswerRepository answerRepo;
    private final ExamAnswerOptionRepository answerOptionRepo;
    private final QuestionTextKeyRepository textKeyRepo;
    private final ExamAnswerTextRepository answerTextRepo;
    private final EntityManager entityManager;

    public ExamService(ExamPaperRepository paperRepo, UserRepository userRepo, ExamQuestionRepository questionRepo,
                       QuestionOptionRepository optionRepo, ExamAttemptRepository attemptRepo,
                       ExamAnswerRepository answerRepo, ExamAnswerOptionRepository answerOptionRepo,
                       QuestionTextKeyRepository textKeyRepo, ExamAnswerTextRepository answerTextRepo,
                       EntityManager entityManager) {
        this.paperRepo = paperRepo;
        this.userRepo = userRepo;
        this.questionRepo = questionRepo;
        this.optionRepo = optionRepo;
        this.attemptRepo = attemptRepo;
        this.answerRepo = answerRepo;
        this.answerOptionRepo = answerOptionRepo;
        this.textKeyRepo = textKeyRepo;
        this.answerTextRepo = answerTextRepo;
        this.entityManager = entityManager;
    }

    @Transactional
    public ExamAttempt start(Long paperId, String username) {
        ExamPaper paper = paperRepo.findById(paperId).orElseThrow(() -> new BusinessException(404, "exam paper not found"));
        User user = userRepo.findByUsername(username).orElseThrow(() -> new BusinessException(404, "user not found"));
        // 幂等续考：若该用户该试卷已存在进行中的 attempt，直接返回，避免重复开考刷出无数 IN_PROGRESS 记录
        List<ExamAttempt> inProgress = attemptRepo.findByStatusAndPaperAndUser(AttemptStatus.IN_PROGRESS, paper, user);
        if (inProgress != null && !inProgress.isEmpty()) {
            return inProgress.get(0);
        }
        List<ExamQuestion> questions = questionRepo.findByPaperOrderByOrderIndexAsc(paper);
        ExamAttempt attempt = new ExamAttempt();
        attempt.setPaper(paper);
        attempt.setUser(user);
        attempt.setStartTime(LocalDateTime.now());
        attempt.setStatus(AttemptStatus.IN_PROGRESS);
        attempt.setQuestionCount(questions.size());
        attempt.setCodeGraded(Boolean.FALSE);
        LocalDateTime now = LocalDateTime.now();
        attempt.setCreatedAt(now);
        attempt.setUpdatedAt(now);
        return attemptRepo.save(attempt);
    }

    @Transactional
    public ExamAttempt submit(ExamSubmitRequest req, String username) {
        ExamAttempt attempt = attemptRepo.findById(req.getAttemptId())
                .orElseThrow(() -> new BusinessException(404, "attempt not found"));
        if (!attempt.getUser().getUsername().equals(username)) {
            throw new BusinessException(403, "forbidden");
        }
        if (attempt.getStatus() != AttemptStatus.IN_PROGRESS) {
            throw new BusinessException("attempt already submitted or cancelled");
        }
        Integer dur = attempt.getPaper().getExamDurationMinutes();
        if (dur != null && dur > 0) {
            java.time.LocalDateTime deadline = attempt.getStartTime().plusMinutes(dur);
            // 宽限 5 分钟：超过 deadline + 5min 才判定超时取消；宽限期内仍正常判分，endTime 记录实际提交时间
            java.time.LocalDateTime graceDeadline = deadline.plusMinutes(5);
            if (java.time.LocalDateTime.now().isAfter(graceDeadline)) {
                attempt.setEndTime(java.time.LocalDateTime.now());
                attempt.setStatus(AttemptStatus.CANCELLED);
                attempt.setUpdatedAt(java.time.LocalDateTime.now());
                attemptRepo.save(attempt);
                throw new BusinessException(422, "time exceeded");
            }
        }

        double total = 0.0;
        int correctCount = 0;

        // 对请求内重复出现的同一 questionId 去重（保留最后一次），
        // 避免同一题被多次判分、total 重复累加造成刷分
        java.util.Map<Long, ExamSubmitRequest.AnswerItem> answerByQuestion = new java.util.LinkedHashMap<>();
        if (req.getAnswers() != null) {
            for (ExamSubmitRequest.AnswerItem item : req.getAnswers()) {
                if (item.getQuestionId() != null) {
                    answerByQuestion.put(item.getQuestionId(), item);
                }
            }
        }

        // ---- 批量取数（每类仅 1 次 SQL，替代原循环内逐题查询）----
        // 1) 请求涉及的题目
        java.util.Map<Long, ExamQuestion> questionById = new java.util.HashMap<>();
        if (!answerByQuestion.isEmpty()) {
            for (ExamQuestion q : questionRepo.findAllById(answerByQuestion.keySet())) {
                questionById.put(q.getId(), q);
            }
        }
        // 2) 选择题正确选项（一次 IN 查询）
        java.util.List<Long> choiceQids = new java.util.ArrayList<>();
        for (ExamSubmitRequest.AnswerItem item : answerByQuestion.values()) {
            ExamQuestion q = questionById.get(item.getQuestionId());
            if (q != null && (q.getType() == QuestionType.SINGLE || q.getType() == QuestionType.MULTIPLE || q.getType() == QuestionType.TRUE_FALSE)) {
                choiceQids.add(q.getId());
            }
        }
        java.util.Map<Long, Set<Long>> correctIdsByQuestion = new java.util.HashMap<>();
        for (QuestionOption opt : optionRepo.findByQuestionIdInAndIsCorrectTrue(choiceQids)) {
            correctIdsByQuestion.computeIfAbsent(opt.getQuestion().getId(), k -> new HashSet<>()).add(opt.getId());
        }
        // 3) 请求中被选中的选项实体（一次 IN 查询，用于存在性校验 + 保存作答选项）
        java.util.Set<Long> chosenOptIds = new HashSet<>();
        for (ExamSubmitRequest.AnswerItem item : answerByQuestion.values()) {
            if (item.getOptionIds() != null) chosenOptIds.addAll(item.getOptionIds());
        }
        java.util.Map<Long, QuestionOption> optionById = new java.util.HashMap<>();
        if (!chosenOptIds.isEmpty()) {
            for (QuestionOption o : optionRepo.findAllById(chosenOptIds)) {
                optionById.put(o.getId(), o);
            }
        }
        // 4) 填空参考答案（一次 IN 查询，替代循环内逐题查询）
        java.util.List<Long> fillQids = new java.util.ArrayList<>();
        for (ExamSubmitRequest.AnswerItem item : answerByQuestion.values()) {
            ExamQuestion q = questionById.get(item.getQuestionId());
            if (q != null && q.getType() == QuestionType.FILL_BLANK) {
                fillQids.add(q.getId());
            }
        }
        java.util.Map<Long, List<QuestionTextKey>> fillKeysByQuestion = new java.util.HashMap<>();
        for (QuestionTextKey tk : textKeyRepo.findByQuestionIdInAndKeyType(fillQids, KeyType.FILL)) {
            fillKeysByQuestion.computeIfAbsent(tk.getQuestion().getId(), k -> new java.util.ArrayList<>()).add(tk);
        }
        for (List<QuestionTextKey> keys : fillKeysByQuestion.values()) {
            keys.sort(java.util.Comparator.comparingInt(t -> t.getKeyIndex() != null ? t.getKeyIndex() : 0));
        }

        java.util.List<ExamAnswer> answersToSave = new java.util.ArrayList<>();
        java.util.List<ExamAnswerOption> answerOptionsToSave = new java.util.ArrayList<>();
        java.util.List<ExamAnswerText> answerTextsToSave = new java.util.ArrayList<>();

        for (ExamSubmitRequest.AnswerItem item : answerByQuestion.values()) {
            ExamQuestion question = questionById.get(item.getQuestionId());
            if (question == null) {
                throw new BusinessException(404, "question not found: " + item.getQuestionId());
            }
            if (!question.getPaper().getId().equals(attempt.getPaper().getId())) {
                throw new BusinessException(422, "question does not belong to this paper: " + item.getQuestionId());
            }
            boolean isCorrect;
            double awarded;
            java.util.List<Long> chosenOptionIds = item.getOptionIds() == null ? java.util.List.of() : item.getOptionIds();
            java.util.List<String> textInputs = item.getTextAnswers() != null ? item.getTextAnswers() : java.util.List.of();
            java.util.List<QuestionTextKey> fillKeys = null;
            String codeText = null;

            if (question.getType() == QuestionType.SINGLE || question.getType() == QuestionType.MULTIPLE || question.getType() == QuestionType.TRUE_FALSE) {
                Set<Long> correctIds = correctIdsByQuestion.getOrDefault(question.getId(), java.util.Collections.emptySet());
                Set<Long> chosen = new HashSet<>(chosenOptionIds);
                isCorrect = chosen.equals(correctIds);
                awarded = isCorrect ? question.getScore() : 0.0;
            } else if (question.getType() == QuestionType.FILL_BLANK) {
                fillKeys = fillKeysByQuestion.getOrDefault(question.getId(), java.util.Collections.emptyList());
                int expected = fillKeys.size();
                int matchedCount = 0;
                for (int idx = 0; idx < expected; idx++) {
                    String expectedText = fillKeys.get(idx).getTextValue();
                    String provided = idx < textInputs.size() ? textInputs.get(idx) : null;
                    boolean match = provided != null && expectedText != null && expectedText.trim().equalsIgnoreCase(provided.trim());
                    if (match) matchedCount++;
                }
                // 填空按空给部分分；isCorrect 仍要求全部空匹配才为 true
                isCorrect = expected > 0 && matchedCount == expected;
                awarded = expected > 0 ? question.getScore() * matchedCount / expected : 0.0;
            } else if (question.getType() == QuestionType.CODE) {
                if (!textInputs.isEmpty()) {
                    codeText = String.join("\n", textInputs);
                } else {
                    codeText = "";
                }
                isCorrect = false;
                awarded = 0.0;
            } else {
                isCorrect = false;
                awarded = 0.0;
            }

            ExamAnswer answer = new ExamAnswer();
            ExamAnswerKey key = new ExamAnswerKey(attempt.getId(), question.getId());
            answer.setId(key);
            answer.setAttempt(attempt);
            answer.setQuestion(question);
            answer.setAwardedScore(awarded);
            answer.setIsCorrect(isCorrect);
            answer.setAnsweredAt(LocalDateTime.now());
            answersToSave.add(answer);

            if (question.getType() == QuestionType.SINGLE || question.getType() == QuestionType.MULTIPLE || question.getType() == QuestionType.TRUE_FALSE) {
                for (Long optId : chosenOptionIds) {
                    ExamAnswerOption ao = new ExamAnswerOption();
                    ao.setId(new ExamAnswerOptionKey(attempt.getId(), question.getId(), optId));
                    ao.setAttempt(attempt);
                    ao.setQuestion(question);
                    QuestionOption opt = optionById.get(optId);
                    if (opt == null) {
                        throw new BusinessException(404, "option not found:" + optId);
                    }
                    if (!opt.getQuestion().getId().equals(question.getId())) {
                        throw new BusinessException(422, "option does not belong to question: " + optId);
                    }
                    ao.setOption(opt);
                    ao.setSelectedAt(LocalDateTime.now());
                    answerOptionsToSave.add(ao);
                }
            } else if (question.getType() == QuestionType.FILL_BLANK) {
                int expected = fillKeys != null ? fillKeys.size() : 0;
                for (int idx = 0; idx < expected; idx++) {
                    String provided = idx < textInputs.size() ? textInputs.get(idx) : null;
                    ExamAnswerText eat = new ExamAnswerText();
                    eat.setId(new ExamAnswerTextKey(attempt.getId(), question.getId(), idx + 1));
                    eat.setAttempt(attempt);
                    eat.setQuestion(question);
                    eat.setTextValue(provided != null ? provided : "");
                    eat.setAnsweredAt(LocalDateTime.now());
                    answerTextsToSave.add(eat);
                }
            } else if (question.getType() == QuestionType.CODE) {
                ExamAnswerText eat = new ExamAnswerText();
                eat.setId(new ExamAnswerTextKey(attempt.getId(), question.getId(), 0));
                eat.setAttempt(attempt);
                eat.setQuestion(question);
                eat.setTextValue(codeText != null ? codeText : "");
                eat.setAnsweredAt(LocalDateTime.now());
                answerTextsToSave.add(eat);
            }

            // 总分按每题实际得分累加（支持填空部分分）；正确题数仍只统计完全正确的题目
            total += awarded;
            if (isCorrect) {
                correctCount++;
            }
        }

        // 批量落库：复合主键实体 id 为手动设置，saveAll 会走 merge（先 SELECT 再 INSERT）；
        // 改用 EntityManager.persist 直插，事务提交时按 jdbc.batch_size 批量 INSERT（配合 rewriteBatchedStatements 合并网络往返）
        for (ExamAnswer a : answersToSave) entityManager.persist(a);
        for (ExamAnswerOption ao : answerOptionsToSave) entityManager.persist(ao);
        for (ExamAnswerText t : answerTextsToSave) entityManager.persist(t);

        attempt.setScoreTotal(total);
        attempt.setCorrectCount(correctCount);
        attempt.setEndTime(LocalDateTime.now());
        attempt.setStatus(AttemptStatus.SUBMITTED);
        attempt.setUpdatedAt(LocalDateTime.now());
        return attemptRepo.save(attempt);
    }

    public Page<ExamAttempt> listMyScores(String username, Pageable pageable) {
        User user = userRepo.findByUsername(username).orElseThrow(() -> new BusinessException(404, "user not found"));
        return attemptRepo.findByStatusAndUser(AttemptStatus.SUBMITTED, user, pageable);
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public java.util.Map<String, Object> getAttemptDetail(Long attemptId, String username) {
        ExamAttempt attempt = attemptRepo.findById(attemptId).orElseThrow(() -> new BusinessException(404, "attempt not found"));
        if (username != null && !attempt.getUser().getUsername().equals(username)) {
            throw new BusinessException(403, "forbidden");
        }
        ExamPaper paper = attempt.getPaper();
        List<ExamQuestion> questions = questionRepo.findByPaperOrderByOrderIndexAsc(paper);
        List<Long> qids = new java.util.ArrayList<>();
        for (ExamQuestion q : questions) qids.add(q.getId());
        // 只有交卷(SUBMITTED)后才允许看到参考答案与正确标记；进行中/已取消一律剥离答案
        boolean revealAnswers = attempt.getStatus() == AttemptStatus.SUBMITTED;

        // ---- 批量取数（每类仅 1 次 SQL，消除原循环内 N+1）----
        // 1) 所有题目选项
        java.util.Map<Long, List<QuestionOption>> optionsByQuestion =
                optionRepo.findByQuestionIdIn(qids).stream()
                        .collect(java.util.stream.Collectors.groupingBy(o -> o.getQuestion().getId()));
        // 2) 该 attempt 的全部作答选项
        java.util.Map<Long, java.util.Set<Long>> chosenByQuestion =
                answerOptionRepo.findByAttempt(attempt).stream()
                        .collect(java.util.stream.Collectors.groupingBy(
                                o -> o.getId().getQuestionId(),
                                java.util.stream.Collectors.mapping(o -> o.getId().getOptionId(),
                                        java.util.stream.Collectors.toSet())));
        // 3) 该 attempt 的全部作答文本
        java.util.Map<Long, List<ExamAnswerText>> textsByQuestion =
                answerTextRepo.findByAttempt(attempt).stream()
                        .collect(java.util.stream.Collectors.groupingBy(t -> t.getId().getQuestionId()));
        // 4) 填空参考答案 / 代码参考答案
        java.util.Map<Long, List<QuestionTextKey>> fillKeysByQuestion =
                textKeyRepo.findByQuestionIdInAndKeyType(qids, KeyType.FILL).stream()
                        .collect(java.util.stream.Collectors.groupingBy(t -> t.getQuestion().getId()));
        java.util.Map<Long, List<QuestionTextKey>> codeKeysByQuestion =
                textKeyRepo.findByQuestionIdInAndKeyType(qids, KeyType.CODE).stream()
                        .collect(java.util.stream.Collectors.groupingBy(t -> t.getQuestion().getId()));

        java.util.List<Object> options = new java.util.ArrayList<>();
        for (ExamQuestion q : questions) {
            List<QuestionOption> qOptions = optionsByQuestion.getOrDefault(q.getId(), java.util.Collections.emptyList());
            for (QuestionOption opt : qOptions) {
                if (revealAnswers) {
                    options.add(opt);
                } else {
                    java.util.Map<String, Object> safe = new java.util.HashMap<>();
                    safe.put("id", opt.getId());
                    safe.put("questionId", q.getId());
                    safe.put("label", opt.getLabel());
                    safe.put("content", opt.getContent());
                    options.add(safe);
                }
            }
        }
        List<ExamAnswer> answers = answerRepo.findByAttempt(attempt);
        java.util.Map<Long, ExamAnswer> byQ = answers.stream().collect(java.util.stream.Collectors.toMap(a -> a.getQuestion().getId(), a -> a));
        List<java.util.Map<String, Object>> answerItems = new java.util.ArrayList<>();
        for (ExamQuestion q : questions) {
            ExamAnswer a = byQ.get(q.getId());
            java.util.Set<Long> chosenIds;
            java.util.List<String> textVals;
            Double awarded;
            Boolean isCorrect;
            if (a != null) {
                chosenIds = chosenByQuestion.getOrDefault(q.getId(), java.util.Collections.emptySet());
                List<ExamAnswerText> texts = textsByQuestion.getOrDefault(q.getId(), java.util.Collections.emptyList());
                texts.sort(java.util.Comparator.comparingInt(t -> t.getId().getTextIndex() != null ? t.getId().getTextIndex() : 0));
                textVals = new java.util.ArrayList<>();
                for (ExamAnswerText t : texts) textVals.add(t.getTextValue());
                awarded = a.getAwardedScore();
                isCorrect = a.getIsCorrect();
            } else {
                chosenIds = java.util.Collections.emptySet();
                textVals = java.util.Collections.emptyList();
                awarded = 0.0;
                isCorrect = false;
            }
            java.util.Map<String, Object> item = new java.util.HashMap<>();
            item.put("questionId", q.getId());
            item.put("questionType", q.getType());
            item.put("awardedScore", awarded);
            item.put("isCorrect", isCorrect);
            item.put("optionIds", chosenIds);
            item.put("textAnswers", textVals);
            if (q.getType() == QuestionType.CODE && revealAnswers) {
                item.put("aiFeedback", a != null ? a.getAiFeedback() : null);
                List<QuestionTextKey> refs = codeKeysByQuestion.getOrDefault(q.getId(), java.util.Collections.emptyList());
                String ref = refs.isEmpty() ? null : refs.get(0).getTextValue();
                item.put("expectedCode", ref);
            }
            if (q.getType() == QuestionType.FILL_BLANK && revealAnswers) {
                List<QuestionTextKey> keys = fillKeysByQuestion.getOrDefault(q.getId(), java.util.Collections.emptyList());
                keys.sort(java.util.Comparator.comparingInt(t -> t.getKeyIndex() != null ? t.getKeyIndex() : 0));
                java.util.List<String> expected = new java.util.ArrayList<>();
                for (QuestionTextKey t : keys) expected.add(t.getTextValue());
                item.put("expectedTextAnswers", expected);
            }
            answerItems.add(item);
        }
        return java.util.Map.of(
                "attempt", attempt,
                "paper", paper,
                "questions", questions,
                "options", options,
                "answers", answerItems
        );
    }
}
