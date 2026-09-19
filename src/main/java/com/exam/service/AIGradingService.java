package com.exam.service;

import com.exam.exception.BusinessException;
import com.exam.model.entity.*;
import com.exam.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Async;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AIGradingService {
    private final ExamPaperRepository paperRepo;
    private final ExamAttemptRepository attemptRepo;
    private final ExamAnswerRepository answerRepo;
    private final ExamAnswerTextRepository answerTextRepo;
    private final QuestionTextKeyRepository textKeyRepo;
    private final ExamQuestionRepository questionRepo;

    private final com.exam.repository.AiConfigRepository configRepo;

    public AIGradingService(ExamPaperRepository paperRepo, ExamAttemptRepository attemptRepo,
                            ExamAnswerRepository answerRepo, ExamAnswerTextRepository answerTextRepo,
                            QuestionTextKeyRepository textKeyRepo, ExamQuestionRepository questionRepo,
                            com.exam.repository.AiConfigRepository configRepo) {
        this.paperRepo = paperRepo;
        this.attemptRepo = attemptRepo;
        this.answerRepo = answerRepo;
        this.answerTextRepo = answerTextRepo;
        this.textKeyRepo = textKeyRepo;
        this.questionRepo = questionRepo;
        this.configRepo = configRepo;
    }

    private final ConcurrentHashMap<String, Map<String, Object>> tasks = new ConcurrentHashMap<>();

    // 异步启动试卷AI grading任务
    public String startGradePaperAIAsync(Long paperId, List<Long> attemptIdsOpt) {
        String id = java.util.UUID.randomUUID().toString();
        Map<String, Object> init = new java.util.HashMap<>();
        init.put("status", "PENDING");
        init.put("createdAt", java.time.LocalDateTime.now());
        tasks.put(id, init);
        runGradePaperAITask(id, paperId, attemptIdsOpt);
        return id;
    }

    @Async("aiGradingExecutor")
    public void runGradePaperAITask(String taskId, Long paperId, List<Long> attemptIdsOpt) {
        try {
            Map<String, Object> res = gradePaperAI(paperId, attemptIdsOpt);
            Map<String, Object> done = new java.util.HashMap<>();
            done.put("status", "DONE");
            done.put("result", res);
            done.put("updatedAt", java.time.LocalDateTime.now());
            tasks.put(taskId, done);
        } catch (Exception e) {
            Map<String, Object> err = new java.util.HashMap<>();
            err.put("status", "ERROR");
            err.put("error", e.getMessage());
            err.put("updatedAt", java.time.LocalDateTime.now());
            tasks.put(taskId, err);
        }
    }

    public Map<String, Object> getTaskStatus(String taskId) {
        Map<String, Object> m = tasks.get(taskId);
        if (m == null) throw new BusinessException(404, "task not found");
        return m;
    }

    public Map<String, Object> gradePaperAI(Long paperId, List<Long> attemptIdsOpt) {
        ExamPaper paper = paperRepo.findById(paperId).orElseThrow(() -> new BusinessException(404, "exam paper not found"));
        if (paper.getCodeGradingMode() != CodeGradingMode.AI) {
            throw new BusinessException(422, "paper not in AI grading mode");
        }
        List<ExamAttempt> attempts;
        if (attemptIdsOpt != null && !attemptIdsOpt.isEmpty()) {
            attempts = new ArrayList<>();
            for (Long id : attemptIdsOpt) {
                ExamAttempt a = attemptRepo.findById(id).orElseThrow(() -> new BusinessException(404, "attempt not found: " + id));
                if (!Objects.equals(a.getPaper().getId(), paperId) || a.getStatus() != AttemptStatus.SUBMITTED) continue;
                attempts.add(a);
            }
        } else {
            attempts = attemptRepo.findByStatusAndPaper(AttemptStatus.SUBMITTED, paper);
        }
        List<ExamAttempt> toProcess = new ArrayList<>();
        int skipped = 0;
        for (ExamAttempt a : attempts) {
            if (Boolean.TRUE.equals(a.getCodeGraded())) { skipped++; continue; }
            toProcess.add(a);
        }

        List<Map<String, Object>> updated = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        for (ExamAttempt attempt : toProcess) {
            try {
                Map<String, Object> res = gradeAttemptAI(attempt, paper.getAiPromptVersion() != null ? paper.getAiPromptVersion() : 1);
                updated.add(res);
            } catch (Exception e) {
                errors.add(Map.of("attemptId", attempt.getId(), "error", e.getMessage()));
            }
        }
        // 全部失败（例如 API Key 未配置）时，把原因抛给上层，让任务以 ERROR 结束，前端明确提示失败而非“成功”
        if (updated.isEmpty() && !errors.isEmpty()) {
            Object firstErr = errors.get(0).get("error");
            throw new BusinessException(500, firstErr != null ? firstErr.toString() : "AI 批改失败，请查看详情");
        }
        return Map.of("processed", toProcess.size(), "skipped", skipped, "updatedAttempts", updated, "errors", errors);
    }

    private Map<String, Object> gradeAttemptAI(ExamAttempt attempt, int promptVersion) {
        List<ExamQuestion> questions = questionRepo.findByPaperOrderByOrderIndexAsc(attempt.getPaper());
        List<Map<String, Object>> items = new ArrayList<>();
        for (ExamQuestion q : questions) {
            if (q.getType() != QuestionType.CODE) continue;
            List<ExamAnswerText> texts = answerTextRepo.findByAttemptAndQuestion(attempt, q);
            String userCode = texts.stream()
                    .sorted(Comparator.comparingInt(t -> t.getId().getTextIndex() != null ? t.getId().getTextIndex() : 0))
                    .map(ExamAnswerText::getTextValue).reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
            List<QuestionTextKey> refs = textKeyRepo.findByQuestionAndKeyTypeOrderByKeyIndexAsc(q, KeyType.CODE);
            String ref = refs.isEmpty() ? "" : refs.get(0).getTextValue();
            items.add(Map.of(
                    "questionId", q.getId(),
                    "max_score", q.getScore(),
                    "question", q.getContent(),
                    "reference", ref,
                    "user_code", userCode
            ));
        }
        if (items.isEmpty()) return Map.of("attemptId", attempt.getId(), "results", List.of());

        String inputJson = toJson(Map.of("items", items));
        String prompt = buildPrompt(promptVersion, inputJson);
        Map<String, Object> modelOut = callDeepSeek(prompt);
        List<Map<String, Object>> results = (List<Map<String, Object>>) modelOut.getOrDefault("results", List.of());

        List<ExamAnswer> answers = answerRepo.findByAttempt(attempt);
        Map<Long, ExamAnswer> byQ = new HashMap<>();
        for (ExamAnswer a : answers) {
            if (a.getQuestion().getType() == QuestionType.CODE) byQ.put(a.getQuestion().getId(), a);
        }
        for (Map<String, Object> r : results) {
            Long qid = ((Number) r.get("questionId")).longValue();
            Double score = toDoubleSafe(r.get("score"));
            String feedback = r.get("feedback") != null ? r.get("feedback").toString() : null;
            ExamAnswer a = byQ.get(qid);
            if (a != null) {
                double max = a.getQuestion().getScore() != null ? a.getQuestion().getScore() : 0.0;
                double clipped = Math.max(0.0, Math.min(max, score != null ? score : 0.0));
                a.setAwardedScore(clipped);
                a.setIsCorrect(clipped >= max);
                if (feedback != null && !feedback.isBlank()) a.setAiFeedback(feedback);
                answerRepo.save(a);
            }
        }
        double total = answers.stream().mapToDouble(x -> x.getAwardedScore() != null ? x.getAwardedScore() : 0.0).sum();
        int correctCount = (int) answers.stream().filter(x -> Boolean.TRUE.equals(x.getIsCorrect())).count();
        attempt.setScoreTotal(total);
        attempt.setCorrectCount(correctCount);
        attempt.setUpdatedAt(java.time.LocalDateTime.now());
        attempt.setCodeGraded(true);
        attemptRepo.save(attempt);

        return Map.of("attemptId", attempt.getId(), "results", results);
    }

    private String buildPrompt(int version, String inputJson) {
        String header =
                "你是一个严格但公平的代码阅卷老师，请独立为每道代码题评分。\n" +
                "- 只输出合法的 JSON 对象；每个结果项必须包含字段：questionId、score（0~max_score，一位小数）、feedback（中文评语）\n" +
                "- 评分优先考虑功能正确性，同时兼顾边界情况、性能与代码质量\n" +
                "- 参考权重：功能60%、边界20%、质量/性能20%；若设计权衡合理可给予部分分\n" +
                "- 评语必须使用中文，且必须包含清晰的扣分明细：逐条指出用户在哪一步或哪个检查项被扣分，以及扣分原因（例如：缺少空数组处理-扣2分；时间复杂度过高-扣1分）\n" +
                "输入：";
        String out = "\n输出示例：\n{" +
                "\n  \"results\": [\n    {\"questionId\": 0, \"score\": 0.0, \"feedback\": \"总体评价：...；扣分明细：1) 未处理空输入-扣2分；2) 未覆盖重复元素-扣1分；改进建议：...\" }\n  ]\n}";
        return header + inputJson + out;
    }

    private Map<String, Object> callDeepSeek(String prompt) {
        // 只使用设置页（ai_config）保存的 API Key；未配置则明确报错，不悄悄回退默认 key
        AiConfig cfg = configRepo.findById(1L).orElse(null);
        String apiKey = (cfg != null && cfg.getApiKey() != null && !cfg.getApiKey().isBlank())
                ? cfg.getApiKey().trim() : null;
        if (apiKey == null || apiKey.isBlank()) {
            throw new BusinessException(500, "AI API Key 未配置，请先在「AI 配置」页填写 API Key 后保存");
        }
        // 模型与接口地址：有配置则用配置，否则回退 DeepSeek 默认
        String model = (cfg != null && cfg.getModel() != null && !cfg.getModel().isBlank()) ? cfg.getModel().trim() : "deepseek-chat";
        String baseUrl = (cfg != null && cfg.getBaseUrl() != null && !cfg.getBaseUrl().isBlank()) ? cfg.getBaseUrl().trim() : "https://api.deepseek.com/v1";
        String endpoint = baseUrl.endsWith("/chat/completions") ? baseUrl : baseUrl + (baseUrl.endsWith("/") ? "" : "/") + "chat/completions";
        String body = toJson(Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "temperature", 0.2,
                "response_format", Map.of("type", "json_object")
        ));
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() >= 300) throw new BusinessException(502, "ai http error: " + resp.statusCode() + " " + resp.body());
            Map<String, Object> parsed = parseJson(resp.body());
            List<Map<String, Object>> choices = (List<Map<String, Object>>) parsed.get("choices");
            if (choices == null || choices.isEmpty()) throw new BusinessException("ai empty choices");
            Map<String, Object> msg = (Map<String, Object>) choices.get(0).get("message");
            String content = (String) msg.get("content");
            if (content == null || content.isBlank()) throw new BusinessException("ai empty content");
            String cleaned = sanitizeJson(content);
            Map<String, Object> out = parseJson(cleaned);
            return out;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("ai call failed: " + e.getMessage());
        }
    }
    private String toJson(Object obj) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
            return m.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String s) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
            return m.readValue(s, java.util.Map.class);
        } catch (Exception e) {
            throw new BusinessException("invalid json from deepseek: " + e.getMessage());
        }
    }

    private Double toDoubleSafe(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).doubleValue();
        try { return Double.parseDouble(o.toString()); } catch (Exception e) { return null; }
    }

    private String sanitizeJson(String s) {
        if (s == null) return "{}";
        String t = s.trim();
        if (t.startsWith("```")) {
            t = t.replaceFirst("^```(json)?", "").replaceAll("```$", "").trim();
        }
        t = t.replace("\r", "").trim();
        int idxStart = t.indexOf('{');
        int idxEnd = t.lastIndexOf('}');
        if (idxStart >= 0 && idxEnd > idxStart) {
            return t.substring(idxStart, idxEnd + 1).trim();
        }
        return t;
    }
}
