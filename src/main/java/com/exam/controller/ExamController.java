package com.exam.controller;

import com.exam.model.dto.request.ExamSubmitRequest;
import com.exam.model.entity.ExamAttempt;
import com.exam.security.SecurityUtils;
import com.exam.service.ExamService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/exams")
public class ExamController {
    private final ExamService examService;

    public ExamController(ExamService examService) {
        this.examService = examService;
    }

    /** 我的成绩允许排序的字段白名单（非法字段返回 422 而非 500） */
    private static final java.util.Set<String> ALLOWED_MY_SCORE_SORTS =
            java.util.Set.of("scoreTotal", "endTime", "startTime", "correctCount", "questionCount", "createdAt", "id");

    @PostMapping("/{paperId}/start")
    public ResponseEntity<ExamAttempt> start(@PathVariable("paperId") Long paperId) {
        return ResponseEntity.ok(examService.start(paperId, SecurityUtils.currentUsername()));
    }

    @PostMapping("/submit")
    public ResponseEntity<ExamAttempt> submit(@RequestBody ExamSubmitRequest req) {
        return ResponseEntity.ok(examService.submit(req, SecurityUtils.currentUsername()));
    }

    @GetMapping("/mine")
    public ResponseEntity<Page<ExamAttempt>> myScores(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size,
            @RequestParam(value = "sort", required = false) String sortParam
    ) {
        Pageable pageable;
        size = Math.min(size, 100);
        if (sortParam == null || sortParam.isBlank()) {
            // 默认 endTime 倒序 + id 倒序做稳定次级键：命中 idx_user_status_endtime 免 filesort
            pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "endTime").and(Sort.by(Sort.Direction.DESC, "id")));
        } else {
            String[] sp = sortParam.split(",");
            String field = sp[0].trim();
            if (!ALLOWED_MY_SCORE_SORTS.contains(field)) {
                throw new com.exam.exception.BusinessException(422, "invalid sort field: " + field);
            }
            Sort.Direction dir = sp.length > 1 && sp[1].equalsIgnoreCase("desc") ? Sort.Direction.DESC : Sort.Direction.ASC;
            pageable = PageRequest.of(page, size, Sort.by(dir, field));
        }
        return ResponseEntity.ok(examService.listMyScores(SecurityUtils.currentUsername(), pageable));
    }

    @GetMapping("/attempts/{attemptId}")
    public ResponseEntity<Map<String, Object>> attemptDetail(@PathVariable("attemptId") Long attemptId) {
        return ResponseEntity.ok(examService.getAttemptDetail(attemptId, SecurityUtils.currentUsername()));
    }
}
