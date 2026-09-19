package com.exam.service;

import com.exam.model.entity.ExamPaper;
import com.exam.repository.ExamPaperRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class PaperPublishScheduler {
    private final ExamPaperRepository paperRepo;

    public PaperPublishScheduler(ExamPaperRepository paperRepo) {
        this.paperRepo = paperRepo;
    }

    @Scheduled(fixedDelay = 60000)
    public void publishDuePapers() {
        LocalDateTime now = LocalDateTime.now();
        List<ExamPaper> list = paperRepo.findByIsPublishedFalseAndVisibleFromNotNullAndVisibleFromBefore(now);
        for (ExamPaper p : list) {
            p.setIsPublished(true);
            p.setUpdatedAt(now);
            paperRepo.save(p);
        }
    }
}