package com.exam.controller;

import com.exam.model.entity.ExamAttempt;
import com.exam.model.entity.ExamPaper;
import com.exam.service.AdminService;
import com.exam.service.AIGradingService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.exam.service.ExamService;
import com.exam.security.SecurityUtils;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final AdminService adminService;
    private final ExamService examService;
    private final AIGradingService aiGradingService;

    public AdminController(AdminService adminService, ExamService examService, AIGradingService aiGradingService) {
        this.adminService = adminService;
        this.examService = examService;
        this.aiGradingService = aiGradingService;
    }

    /** 成绩列表允许排序的字段白名单（与 exam_attempt 实体属性对齐；非法字段返回 422 而非 500） */
    private static final java.util.Set<String> ALLOWED_SCORE_SORTS =
            java.util.Set.of("scoreTotal", "endTime", "startTime", "correctCount", "questionCount", "createdAt", "id");

    @PostMapping("/users/import")
    public ResponseEntity<Map<String, Object>> importUsers(@RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(adminService.importUsers(file));
    }

    @PostMapping("/papers/import")
    public ResponseEntity<ExamPaper> importPaper(@RequestParam("file") MultipartFile file,
                                                 @RequestParam(value = "publishAt", required = false) String publishAt,
                                                 @RequestParam(value = "durationMinutes", required = false) Integer durationMinutes) {
        String username = SecurityUtils.currentUsername();
        return ResponseEntity.ok(adminService.importPaper(file, username, publishAt, durationMinutes));
    }

    // 删除试卷
    @DeleteMapping("/papers/{id}")
    public ResponseEntity<java.util.Map<String, Object>> deletePaper(@PathVariable("id") Long id) {
        adminService.deletePaper(id);
        return ResponseEntity.ok(java.util.Map.of("message", "success"));
    }

    @GetMapping("/scores")
    public ResponseEntity<java.util.Map<String, Object>> pageScores(
            @RequestParam(value = "paperId", required = false) Long paperId,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "graded", required = false) Boolean graded,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size,
            @RequestParam(value = "sort", required = false) String sortParam
    ) {
        Pageable pageable;
        size = Math.min(size, 100);
        if (sortParam == null || sortParam.isBlank()) {
            // 默认 scoreTotal 倒序 + id 倒序做稳定次级键：同分不漂移，且命中 idx_status_score_id 免 filesort
            pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "scoreTotal").and(Sort.by(Sort.Direction.DESC, "id")));
        } else {
            String[] sp = sortParam.split(",");
            String field = sp[0].trim();
            if (!ALLOWED_SCORE_SORTS.contains(field)) {
                throw new com.exam.exception.BusinessException(422, "invalid sort field: " + field);
            }
            Sort.Direction dir = sp.length > 1 && sp[1].equalsIgnoreCase("desc") ? Sort.Direction.DESC : Sort.Direction.ASC;
            pageable = PageRequest.of(page, size, Sort.by(dir, field));
        }
        Page<ExamAttempt> pg = adminService.pageScores(paperId, userId, name, graded, pageable);
        java.util.Map<String, Object> resp = new java.util.HashMap<>();
        resp.put("content", pg.getContent());
        resp.put("totalElements", pg.getTotalElements());
        resp.put("totalPages", pg.getTotalPages());
        resp.put("number", pg.getNumber());
        resp.put("size", pg.getSize());
        resp.put("stats", adminService.scoreStats(paperId, userId, name, graded));
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/papers/names")
    public ResponseEntity<java.util.List<java.util.Map<String, Object>>> paperNames() {
        return ResponseEntity.ok(adminService.listPaperNames());
    }

    // 管理端：试卷详情 + 标准答案（含正确选项/填空答案/代码参考），供"查看详情/渲染正确答案"使用
    @GetMapping("/papers/{paperId}/with-answers")
    public ResponseEntity<java.util.Map<String, Object>> paperWithAnswers(@PathVariable("paperId") Long paperId) {
        return ResponseEntity.ok(adminService.paperAnswersForAdmin(paperId));
    }

    @GetMapping("/attempts/{attemptId}")
    public ResponseEntity<java.util.Map<String, Object>> attemptDetailAdmin(@PathVariable("attemptId") Long attemptId) {
        return ResponseEntity.ok(examService.getAttemptDetail(attemptId, null));
    }

    @GetMapping("/papers/{paperId}/scores/export")
    public ResponseEntity<byte[]> exportScores(@PathVariable("paperId") Long paperId) {
        byte[] bytes = adminService.exportScoresExcel(paperId, null);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=paper-" + paperId + "-scores.xlsx")
                .contentType(org.springframework.http.MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }

    // 通用成绩导出：可选按试卷 / 用户过滤，未选试卷时导出全部提交
    @GetMapping("/scores/export")
    public ResponseEntity<byte[]> exportScoresFiltered(
            @RequestParam(value = "paperId", required = false) Long paperId,
            @RequestParam(value = "userId", required = false) Long userId) {
        byte[] bytes = adminService.exportScoresExcel(paperId, userId);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=exam-scores.xlsx")
                .contentType(org.springframework.http.MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }

    // 下载用户导入 Excel 模板
    @GetMapping("/users/import-template")
    public ResponseEntity<byte[]> userImportTemplate() {
        byte[] bytes = adminService.buildUserImportTemplate();
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=user-import-template.xlsx")
                .contentType(org.springframework.http.MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }

    // 下载试卷导入 Markdown 模板
    @GetMapping("/papers/import-template")
    public ResponseEntity<byte[]> paperImportTemplate() {
        byte[] bytes = adminService.buildPaperImportTemplate();
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=paper-import-template.md")
                .contentType(org.springframework.http.MediaType.parseMediaType("text/markdown"))
                .body(bytes);
    }

    @GetMapping("/attempts/{attemptId}/code-answers")
    public ResponseEntity<java.util.List<java.util.Map<String, Object>>> listCodeAnswers(@PathVariable("attemptId") Long attemptId) {
        return ResponseEntity.ok(adminService.listCodeAnswersForAttempt(attemptId));
    }

    // 手动打分
    @PostMapping("/attempts/{attemptId}/grade-code")
    public ResponseEntity<java.util.Map<String, Object>> gradeCode(
            @PathVariable("attemptId") Long attemptId,
            @RequestBody com.exam.model.dto.request.AdminCodeGradeRequest body
    ) {
        return ResponseEntity.ok(adminService.gradeCodeAnswers(attemptId, body.getGrades()));
    }

    // 手动打分
    @PostMapping("/grading/{attemptId}")
    public ResponseEntity<java.util.Map<String, Object>> gradeManual(
            @PathVariable("attemptId") Long attemptId,
            @RequestBody com.exam.model.dto.request.AdminCodeGradeRequest body
    ) {
        return ResponseEntity.ok(adminService.gradeCodeAnswers(attemptId, body.getGrades()));
    }

    @DeleteMapping("/attempts/{attemptId}")
    public ResponseEntity<Void> deleteAttempt(@PathVariable("attemptId") Long attemptId) {
        adminService.deleteAttempt(attemptId);
        return ResponseEntity.status(200).build();
    }

    @GetMapping("/attempts/user/{userId}/paper/{paperId}")
    public ResponseEntity<java.util.Map<String, Object>> attemptDetailByUserAndPaper(
            @PathVariable("userId") Long userId,
            @PathVariable("paperId") Long paperId
    ) {
        java.util.Optional<com.exam.model.entity.ExamAttempt> opt = adminService.getLatestSubmittedAttempt(userId, paperId);
        if (opt.isEmpty()) {
            throw new com.exam.exception.BusinessException(404, "attempt not found for user/paper");
        }
        return ResponseEntity.ok(examService.getAttemptDetail(opt.get().getId(), null));
    }

    @GetMapping("/users")
    public ResponseEntity<org.springframework.data.domain.Page<com.exam.model.entity.User>> pageUsers(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size,
            @RequestParam(value = "sort", required = false) String sortParam
    ) {
        org.springframework.data.domain.Pageable pageable;
        if (sortParam == null || sortParam.isBlank()) {
            pageable = org.springframework.data.domain.PageRequest.of(page, size, org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));
        } else {
            String[] sp = sortParam.split(",");
            org.springframework.data.domain.Sort.Direction dir = sp.length > 1 && sp[1].equalsIgnoreCase("desc") ? org.springframework.data.domain.Sort.Direction.DESC : org.springframework.data.domain.Sort.Direction.ASC;
            pageable = org.springframework.data.domain.PageRequest.of(page, size, org.springframework.data.domain.Sort.by(dir, sp[0]));
        }
        return ResponseEntity.ok(adminService.pageUsers(pageable));
    }

    @GetMapping("/users/search")
    public ResponseEntity<org.springframework.data.domain.Page<com.exam.model.entity.User>> searchUsers(
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size,
            @RequestParam(value = "sort", required = false) String sortParam
    ) {
        org.springframework.data.domain.Pageable pageable;
        if (sortParam == null || sortParam.isBlank()) {
            pageable = org.springframework.data.domain.PageRequest.of(page, size, org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));
        } else {
            String[] sp = sortParam.split(",");
            org.springframework.data.domain.Sort.Direction dir = sp.length > 1 && sp[1].equalsIgnoreCase("desc") ? org.springframework.data.domain.Sort.Direction.DESC : org.springframework.data.domain.Sort.Direction.ASC;
            pageable = org.springframework.data.domain.PageRequest.of(page, size, org.springframework.data.domain.Sort.by(dir, sp[0]));
        }
        return ResponseEntity.ok(adminService.searchUsers(name, pageable));
    }

    @GetMapping("/users/by-role")
    public ResponseEntity<org.springframework.data.domain.Page<com.exam.model.entity.User>> usersByRole(
            @RequestParam(value = "role", required = false) String role,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size,
            @RequestParam(value = "sort", required = false) String sortParam
    ) {
        org.springframework.data.domain.Pageable pageable;
        if (sortParam == null || sortParam.isBlank()) {
            pageable = org.springframework.data.domain.PageRequest.of(page, size, org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));
        } else {
            String[] sp = sortParam.split(",");
            org.springframework.data.domain.Sort.Direction dir = sp.length > 1 && sp[1].equalsIgnoreCase("desc") ? org.springframework.data.domain.Sort.Direction.DESC : org.springframework.data.domain.Sort.Direction.ASC;
            pageable = org.springframework.data.domain.PageRequest.of(page, size, org.springframework.data.domain.Sort.by(dir, sp[0]));
        }
        return ResponseEntity.ok(adminService.pageUsersByRole(role, pageable));
    }

    @PostMapping("/users/reset-passwords")
    public ResponseEntity<java.util.Map<String, Object>> resetAllPasswords(@RequestBody java.util.Map<String, String> body) {
        String newPassword = body != null ? body.get("newPassword") : null;
        return ResponseEntity.ok(adminService.resetAllPasswords(newPassword));
    }

    @PostMapping("/users/{userId}/reset-password")
    public ResponseEntity<java.util.Map<String, Object>> resetUserPassword(
            @PathVariable("userId") Long userId,
            @RequestBody java.util.Map<String, String> body
    ) {
        String newPassword = body != null ? body.get("newPassword") : null;
        return ResponseEntity.ok(adminService.resetUserPassword(userId, newPassword));
    }

    @PostMapping("/users")
    public ResponseEntity<com.exam.model.entity.User> createUser(@RequestBody java.util.Map<String, String> body) {
        return ResponseEntity.ok(adminService.createUser(body));
    }

    @DeleteMapping("/users")
    public ResponseEntity<java.util.Map<String, Object>> deleteUsers(@RequestParam("ids") java.util.List<Long> ids) {
        return ResponseEntity.ok(adminService.deleteUsers(ids));
    }

    @PutMapping("/papers/{paperId}/grading-mode")
    public ResponseEntity<Void> setGradingMode(
            @PathVariable("paperId") Long paperId,
            @RequestBody com.exam.model.dto.request.SetGradingModeRequest body
    ) {
        adminService.setPaperGradingMode(paperId, body.getMode(), body.getPromptVersion());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/papers/{paperId}/grade-code/ai")
    public ResponseEntity<java.util.Map<String, Object>> gradeCodeAI(
            @PathVariable("paperId") Long paperId,
            @RequestBody(required = false) java.util.Map<String, Object> body
    ) {
        java.util.List<Long> attempts = null;
        if (body != null) {
            Object v = body.get("attemptIds");
            if (v instanceof java.util.List<?> l) {
                attempts = l.stream()
                        .map(x -> {
                            if (x instanceof Number) return ((Number) x).longValue();
                            String s = x != null ? x.toString() : null;
                            return (s != null && !s.isBlank()) ? Long.parseLong(s) : null;
                        })
                        .filter(java.util.Objects::nonNull)
                        .toList();
            } else if (v instanceof String s) {
                attempts = java.util.Arrays.stream(s.split(","))
                        .map(String::trim)
                        .filter(t -> !t.isEmpty())
                        .map(Long::parseLong)
                        .toList();
            }
        }
        Boolean async = body != null && body.get("async") instanceof Boolean ? (Boolean) body.get("async") : Boolean.FALSE;
        if (Boolean.TRUE.equals(async)) {
            String taskId = aiGradingService.startGradePaperAIAsync(paperId, attempts);
            return ResponseEntity.ok(java.util.Map.of("taskId", taskId));
        } else {
            return ResponseEntity.ok(aiGradingService.gradePaperAI(paperId, attempts));
        }
    }

    @GetMapping("/grading/tasks/{taskId}")
    public ResponseEntity<java.util.Map<String, Object>> gradingTaskStatus(
            @PathVariable("taskId") String taskId
    ) {
        return ResponseEntity.ok(aiGradingService.getTaskStatus(taskId));
    }
}
