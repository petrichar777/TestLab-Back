package com.exam.controller;

import com.exam.model.entity.ExamPaper;
import com.exam.service.PaperService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/papers")
public class PaperController {
    private final PaperService paperService;

    public PaperController(PaperService paperService) {
        this.paperService = paperService;
    }

    @GetMapping
    public ResponseEntity<Page<ExamPaper>> list(Pageable pageable) {
        return ResponseEntity.ok(paperService.listPublished(pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<List<Object>> detail(@PathVariable("id") Long id) {
        return ResponseEntity.ok(paperService.detail(id));
    }
}
