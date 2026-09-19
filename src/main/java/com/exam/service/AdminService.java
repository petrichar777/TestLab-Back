package com.exam.service;

import com.exam.exception.BusinessException;
import com.exam.model.entity.*;
import com.exam.repository.*;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class AdminService {
    private final UserRepository userRepo;
    private final ExamPaperRepository paperRepo;
    private final ExamQuestionRepository questionRepo;
    private final QuestionOptionRepository optionRepo;
    private final ExamAttemptRepository attemptRepo;
    private final ExamAnswerRepository answerRepo;
    private final ExamAnswerTextRepository answerTextRepo;
    private final com.exam.repository.ExamAnswerOptionRepository answerOptionRepo;
    private final QuestionTextKeyRepository textKeyRepo;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    // constructor at bottom; grading config is persisted on exam_paper (code_grading_mode / ai_prompt_version)

    @Transactional
    public Map<String, Object> importUsers(MultipartFile file) {
        int imported = 0;
        try (XSSFWorkbook wb = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = wb.getSheetAt(0);
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                String username = getString(row, 0);
                String displayName = getString(row, 1);
                String email = getString(row, 2);
                String password = getString(row, 3);
                if (username == null || username.isBlank()) continue;
                if (userRepo.existsByUsername(username)) continue;
                User u = new User();
                u.setUsername(username);
                u.setDisplayName(displayName != null ? displayName : username);
                u.setEmail(email);
                String raw = (password != null && !password.isBlank()) ? password : (username + "123");
                u.setPasswordHash(passwordEncoder.encode(raw));
                u.setRole(Role.USER);
                u.setStatus(UserStatus.ACTIVE);
                LocalDateTime now = LocalDateTime.now();
                u.setCreatedAt(now);
                u.setUpdatedAt(now);
                userRepo.save(u);
                imported++;
            }
        } catch (IOException e) {
            throw new BusinessException("failed to read excel: " + e.getMessage());
        }
        return Map.of("imported", imported);
    }

    private String getString(Row row, int idx) {
        if (row.getCell(idx) == null) return null;
        row.getCell(idx).setCellType(org.apache.poi.ss.usermodel.CellType.STRING);
        return row.getCell(idx).getStringCellValue();
    }

    @Transactional
    public ExamPaper importPaper(MultipartFile file, String username, String publishAt, Integer durationMinutes) {
        User creator = userRepo.findByUsername(username).orElse(null);
        List<String> lines = readLines(file);
        if (lines.isEmpty()) throw new BusinessException("empty markdown");

        ExamPaper paper = new ExamPaper();
        paper.setTitle(parseTitle(lines));
        paper.setDescription(parseDescription(lines));
        paper.setCode(UUID.randomUUID().toString().substring(0, 8));
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime publishTime = parsePublishAt(publishAt);
        if (publishTime != null && publishTime.isAfter(now)) {
            paper.setIsPublished(false);
            paper.setVisibleFrom(publishTime);
        } else {
            paper.setIsPublished(true);
            paper.setVisibleFrom(null);
        }
        if (durationMinutes != null && durationMinutes > 0) {
            paper.setExamDurationMinutes(durationMinutes);
        }
        paper.setCreatedBy(creator);
        paper.setImportedAt(LocalDateTime.now());
        paper.setSourceMdName(file.getOriginalFilename());
        paper.setSourceMdHash(sha256(String.join("\n", lines)));
        paper.setTotalScore(0.0);
        paper.setCreatedAt(now);
        paper.setUpdatedAt(now);
        paper = paperRepo.save(paper);

        double totalScore = 0.0;
        Integer totalOverride = parseTotalScoreOverride(lines);

        int orderIndex = 1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (!line.startsWith("## ")) continue;
            // ## Qn [TYPE] score=xx
            QuestionType type = extractType(line);
            Double score = extractScore(line);

            String content = collectQuestionContent(lines, i + 1);
            ExamQuestion q = new ExamQuestion();
            q.setPaper(paper);
            q.setType(type);
            q.setContent(content);
            q.setScore(score);
            q.setOrderIndex(orderIndex++);
            q.setCreatedAt(LocalDateTime.now());
            q.setUpdatedAt(LocalDateTime.now());
            q = questionRepo.save(q);

            if (type == QuestionType.SINGLE || type == QuestionType.MULTIPLE || type == QuestionType.TRUE_FALSE) {
                List<String> optionLines = collectOptionLines(lines, i + 1);
                for (String ol : optionLines) {
                    String[] arr = ol.substring(2).split(". ", 2);
                    String label = arr[0].trim();
                    String optContent = arr.length > 1 ? arr[1].trim() : "";
                    QuestionOption opt = new QuestionOption();
                    opt.setQuestion(q);
                    opt.setLabel(label);
                    opt.setContent(optContent);
                    opt.setIsCorrect(false);
                    opt.setCreatedAt(LocalDateTime.now());
                    opt.setUpdatedAt(LocalDateTime.now());
                    optionRepo.save(opt);
                }

                Set<String> answers = extractAnswers(lines, i + 1);
                List<QuestionOption> opts = optionRepo.findByQuestion(q);
                for (QuestionOption opt : opts) {
                    if (answers.contains(opt.getLabel().toUpperCase())) {
                        opt.setIsCorrect(true);
                        optionRepo.save(opt);
                    }
                }
            } else if (type == QuestionType.FILL_BLANK) {
                java.util.List<String> fillKeys = extractFillAnswers(lines, i + 1);
                for (int k = 0; k < fillKeys.size(); k++) {
                    QuestionTextKey tk = new QuestionTextKey();
                    tk.setQuestion(q);
                    tk.setKeyIndex(k + 1);
                    tk.setTextValue(fillKeys.get(k));
                    tk.setKeyType(KeyType.FILL);
                    tk.setCreatedAt(LocalDateTime.now());
                    tk.setUpdatedAt(LocalDateTime.now());
                    textKeyRepo.save(tk);
                }
            } else if (type == QuestionType.CODE) {
                String codeAns = collectAnswerBlock(lines, i + 1);
                if (codeAns != null && !codeAns.isBlank()) {
                    QuestionTextKey tk = new QuestionTextKey();
                    tk.setQuestion(q);
                    tk.setKeyIndex(null);
                    tk.setTextValue(codeAns);
                    tk.setKeyType(KeyType.CODE);
                    tk.setCreatedAt(LocalDateTime.now());
                    tk.setUpdatedAt(LocalDateTime.now());
                    textKeyRepo.save(tk);
                }
            }

            totalScore += score;
        }

        paper.setTotalScore((double) (totalOverride != null ? totalOverride : totalScore));
        paper.setUpdatedAt(LocalDateTime.now());
        return paperRepo.save(paper);
    }

    private LocalDateTime parsePublishAt(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDateTime.parse(s.trim()); } catch (Exception e) { throw new BusinessException(422, "invalid publishAt"); }
    }

    private List<String> readLines(MultipartFile file) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            List<String> list = new ArrayList<>();
            String line;
            while ((line = br.readLine()) != null) {
                list.add(line);
            }
            return list;
        } catch (IOException e) {
            throw new BusinessException("failed to read markdown: " + e.getMessage());
        }
    }

    private String parseTitle(List<String> lines) {
        for (String line : lines) {
            if (line.startsWith("# ")) {
                return line.substring(2).trim();
            }
        }
        return "Untitled";
    }

    private String parseDescription(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (!line.startsWith("# ") && !line.startsWith("## ") && !line.startsWith("TotalScore:")) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString().trim();
    }

    private Integer parseTotalScoreOverride(List<String> lines) {
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("TotalScore:")) {
                try {
                    return Integer.parseInt(t.substring("TotalScore:".length()).trim());
                } catch (NumberFormatException ignored) {}
            }
        }
        return null;
    }

    private QuestionType extractType(String header) {
        int lb = header.indexOf('[');
        int rb = header.indexOf(']');
        if (lb >= 0 && rb > lb) {
            return QuestionType.valueOf(header.substring(lb + 1, rb).trim());
        }
        return QuestionType.SINGLE;
    }

    private Double extractScore(String header) {
        int idx = header.indexOf("score=");
        if (idx >= 0) {
            String s = header.substring(idx + 6).trim();
            try { return Double.parseDouble(s); } catch (Exception ignored) {}
        }
        return 1.0;
    }

    private String collectQuestionContent(List<String> lines, int start) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.startsWith("## ")) break;
            if (l.startsWith("- ")) break;
            if (l.startsWith("Answer:")) break;
            sb.append(l).append('\n');
        }
        return sb.toString().trim();
    }

    private List<String> collectOptionLines(List<String> lines, int start) {
        List<String> opts = new ArrayList<>();
        for (int i = start; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.startsWith("## ")) break;
            if (l.startsWith("Answer:")) break;
            if (l.startsWith("- ")) opts.add(l.trim());
        }
        return opts;
    }

    private Set<String> extractAnswers(List<String> lines, int start) {
        for (int i = start; i < lines.size(); i++) {
            String l = lines.get(i).trim();
            if (l.startsWith("## ")) break;
            if (l.startsWith("Answer:")) {
                String val = l.substring("Answer:".length()).trim();
                String[] parts = val.split(",");
                Set<String> s = new HashSet<>();
                for (String p : parts) s.add(p.trim().toUpperCase());
                return s;
            }
        }
        return Set.of();
    }

    private java.util.List<String> extractFillAnswers(java.util.List<String> lines, int start) {
        for (int i = start; i < lines.size(); i++) {
            String l = lines.get(i).trim();
            if (l.startsWith("## ")) break;
            if (l.startsWith("Answer:")) {
                String val = l.substring("Answer:".length()).trim();
                String[] parts = val.contains("|") ? val.split("\\|") : val.split(",");
                java.util.List<String> res = new java.util.ArrayList<>();
                for (String p : parts) res.add(p.trim());
                return res;
            }
        }
        return java.util.List.of();
    }

    private String collectAnswerBlock(java.util.List<String> lines, int start) {
        boolean seen = false;
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.startsWith("## ")) break;
            if (!seen) {
                if (l.startsWith("Answer:")) {
                    String val = l.substring("Answer:".length());
                    sb.append(val).append('\n');
                    seen = true;
                }
            } else {
                sb.append(l).append('\n');
            }
        }
        String s = sb.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private String sha256(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] b = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    @Transactional
    public void deletePaper(Long id) {
        ExamPaper p = paperRepo.findById(id).orElseThrow(() -> new BusinessException(404, "exam paper not found"));
        java.util.List<ExamAttempt> attempts = new java.util.ArrayList<>();
        attempts.addAll(attemptRepo.findByStatusAndPaper(AttemptStatus.IN_PROGRESS, p));
        attempts.addAll(attemptRepo.findByStatusAndPaper(AttemptStatus.SUBMITTED, p));
        attempts.addAll(attemptRepo.findByStatusAndPaper(AttemptStatus.CANCELLED, p));
        for (ExamAttempt a : attempts) {
            deleteAttempt(a.getId());
        }
        java.util.List<ExamQuestion> questions = questionRepo.findByPaperOrderByOrderIndexAsc(p);
        if (!questions.isEmpty()) {
            textKeyRepo.deleteByQuestionIn(questions);
        }
        paperRepo.delete(p);
    }

    public Page<ExamAttempt> pageScores(Long paperId, Long userId, String name, Boolean graded, Pageable pageable) {
        if ((name != null && !name.isBlank()) || graded != null) {
            return attemptRepo.searchScores(AttemptStatus.SUBMITTED, paperId,
                    (name != null && !name.isBlank()) ? name.trim() : null, graded, pageable);
        }
        if (paperId != null && userId != null) {
            ExamPaper p = paperRepo.findById(paperId).orElseThrow(() -> new BusinessException(404, "exam paper not found"));
            User u = userRepo.findById(userId).orElseThrow(() -> new BusinessException(404, "user not found"));
            return attemptRepo.findByStatusAndPaperAndUser(AttemptStatus.SUBMITTED, p, u, pageable);
        } else if (paperId != null) {
            ExamPaper p = paperRepo.findById(paperId).orElseThrow(() -> new BusinessException(404, "exam paper not found"));
            return attemptRepo.findByStatusAndPaper(AttemptStatus.SUBMITTED, p, pageable);
        } else if (userId != null) {
            User u = userRepo.findById(userId).orElseThrow(() -> new BusinessException(404, "user not found"));
            return attemptRepo.findByStatusAndUser(AttemptStatus.SUBMITTED, u, pageable);
        }
        return attemptRepo.findByStatus(AttemptStatus.SUBMITTED, pageable);
    }

    public java.util.List<java.util.Map<String, Object>> listPaperNames() {
        java.util.List<ExamPaper> all = paperRepo.findAll();
        java.util.List<java.util.Map<String, Object>> res = new java.util.ArrayList<>();
        for (ExamPaper p : all) {
            res.add(java.util.Map.of(
                    "id", p.getId(),
                    "title", p.getTitle(),
                    "codeGradingMode", p.getCodeGradingMode() != null ? p.getCodeGradingMode().name() : com.exam.model.entity.CodeGradingMode.MANUAL.name()
            ));
        }
        return res;
    }

    public java.util.List<java.util.Map<String, Object>> scoreStats(Long paperId, Long userId, String name, Boolean graded) {
        // 全部走 SQL 分组聚合（无筛选 / 按卷 / 按人 / 按卷+人 均覆盖），
        // 避免把全部历史提交捞进 JVM 再内存聚合；paperId/userId 为 null 时传 -1 不过滤
        long paperParam = paperId != null ? paperId : -1L;
        java.util.List<Object[]> rows;
        if ((name != null && !name.isBlank()) || graded != null) {
            rows = attemptRepo.aggregateScores(paperParam,
                    (name != null && !name.isBlank()) ? name.trim() : null, graded);
        } else {
            rows = attemptRepo.aggregateSubmittedStats(paperParam, userId != null ? userId : -1L);
        }
        // 试卷标题一次 IN 取回，避免逐卷 findById 的 N+1
        java.util.Map<Long, String> titleById = new java.util.HashMap<>();
        if (!rows.isEmpty()) {
            java.util.List<Long> pids = new java.util.ArrayList<>();
            for (Object[] r : rows) pids.add(((Number) r[0]).longValue());
            for (ExamPaper p : paperRepo.findAllById(pids)) {
                titleById.put(p.getId(), p.getTitle());
            }
        }
        java.util.List<java.util.Map<String, Object>> result = new java.util.ArrayList<>();
        for (Object[] r : rows) {
            Long pid = ((Number) r[0]).longValue();
            long total = ((Number) r[1]).longValue();
            double min = r[2] == null ? 0.0 : ((Number) r[2]).doubleValue();
            double max = r[3] == null ? 0.0 : ((Number) r[3]).doubleValue();
            double avg = r[4] == null ? 0.0 : ((Number) r[4]).doubleValue();
            result.add(java.util.Map.of(
                    "paperId", pid,
                    "title", titleById.get(pid),
                    "total", total,
                    "min", min,
                    "max", max,
                    "avg", avg
            ));
        }
        return result;
    }

    public java.util.Optional<ExamAttempt> getLatestSubmittedAttempt(Long userId, Long paperId) {
        ExamPaper p = paperRepo.findById(paperId).orElseThrow(() -> new BusinessException(404, "exam paper not found"));
        User u = userRepo.findById(userId).orElseThrow(() -> new BusinessException(404, "user not found"));
        java.util.List<ExamAttempt> list = attemptRepo.findByStatusAndPaperAndUser(AttemptStatus.SUBMITTED, p, u);
        if (list == null || list.isEmpty()) return java.util.Optional.empty();
        list.sort((a, b) -> {
            java.time.LocalDateTime ea = a.getEndTime();
            java.time.LocalDateTime eb = b.getEndTime();
            if (ea == null && eb == null) return 0;
            if (ea == null) return 1;
            if (eb == null) return -1;
            return eb.compareTo(ea);
        });
        return java.util.Optional.of(list.get(0));
    }

    public org.springframework.data.domain.Page<User> pageUsers(org.springframework.data.domain.Pageable pageable) {
        return userRepo.findAll(pageable);
    }

    public org.springframework.data.domain.Page<User> searchUsers(String name, org.springframework.data.domain.Pageable pageable) {
        String n = name != null ? name.trim() : "";
        if (n.isEmpty()) return userRepo.findAll(pageable);
        return userRepo.findByUsernameContainingIgnoreCaseOrDisplayNameContainingIgnoreCase(n, n, pageable);
    }

    public org.springframework.data.domain.Page<User> pageUsersByRole(String roleStr, org.springframework.data.domain.Pageable pageable) {
        if (roleStr == null || roleStr.isBlank()) return userRepo.findAll(pageable);
        com.exam.model.entity.Role role;
        try { role = com.exam.model.entity.Role.valueOf(roleStr.trim().toUpperCase()); } catch (Exception e) { throw new com.exam.exception.BusinessException(422, "invalid role"); }
        return userRepo.findByRole(role, pageable);
    }

    public AdminService(UserRepository userRepo, ExamPaperRepository paperRepo, ExamQuestionRepository questionRepo,
                        QuestionOptionRepository optionRepo, ExamAttemptRepository attemptRepo,
                        QuestionTextKeyRepository textKeyRepo,
                        ExamAnswerRepository answerRepo, ExamAnswerTextRepository answerTextRepo,
                        com.exam.repository.ExamAnswerOptionRepository answerOptionRepo,
                        org.springframework.security.crypto.password.PasswordEncoder passwordEncoder) {
        this.userRepo = userRepo;
        this.paperRepo = paperRepo;
        this.questionRepo = questionRepo;
        this.optionRepo = optionRepo;
        this.attemptRepo = attemptRepo;
        this.answerRepo = answerRepo;
        this.answerTextRepo = answerTextRepo;
        this.textKeyRepo = textKeyRepo;
        this.answerOptionRepo = answerOptionRepo;
        this.passwordEncoder = passwordEncoder;
    }

    @org.springframework.transaction.annotation.Transactional
    public void setPaperGradingMode(Long paperId, String mode, Integer promptVersion) {
        ExamPaper p = paperRepo.findById(paperId).orElseThrow(() -> new BusinessException(404, "exam paper not found"));
        CodeGradingMode m;
        try { m = CodeGradingMode.valueOf(mode); } catch (Exception e) { throw new BusinessException(422, "invalid mode"); }
        p.setCodeGradingMode(m);
        if (promptVersion != null) p.setAiPromptVersion(promptVersion);
        p.setUpdatedAt(java.time.LocalDateTime.now());
        paperRepo.save(p);
    }

    public java.util.List<java.util.Map<String, Object>> listCodeAnswersForAttempt(Long attemptId) {
        ExamAttempt attempt = attemptRepo.findById(attemptId).orElseThrow(() -> new BusinessException(404, "attempt not found"));
        java.util.List<com.exam.model.entity.ExamQuestion> questions = questionRepo.findByPaperOrderByOrderIndexAsc(attempt.getPaper());
        // 一次取回整个 attempt 的作答文本，内存按题目分组（替代循环内 findByAttemptAndQuestion）
        java.util.Map<Long, java.util.List<com.exam.model.entity.ExamAnswerText>> textsByQuestion =
                answerTextRepo.findByAttempt(attempt).stream()
                        .collect(java.util.stream.Collectors.groupingBy(t -> t.getId().getQuestionId()));
        java.util.List<java.util.Map<String, Object>> res = new java.util.ArrayList<>();
        for (ExamQuestion q : questions) {
            if (q.getType() == QuestionType.CODE) {
                java.util.List<com.exam.model.entity.ExamAnswerText> texts = textsByQuestion.getOrDefault(q.getId(), java.util.Collections.emptyList());
                String code = texts.stream().sorted(java.util.Comparator.comparingInt(t -> t.getId().getTextIndex() != null ? t.getId().getTextIndex() : 0))
                        .map(com.exam.model.entity.ExamAnswerText::getTextValue).reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
                res.add(java.util.Map.of(
                        "questionId", q.getId(),
                        "orderIndex", q.getOrderIndex(),
                        "score", q.getScore(),
                        "content", q.getContent(),
                        "code", code
                ));
            }
        }
        return res;
    }

    public byte[] exportScoresExcel(Long paperId, Long userId) {
        ExamPaper p = paperId != null ? paperRepo.findById(paperId).orElseThrow(() -> new BusinessException(404, "exam paper not found")) : null;
        User u = userId != null ? userRepo.findById(userId).orElseThrow(() -> new BusinessException(404, "user not found")) : null;
        java.util.List<ExamAttempt> attempts;
        if (p != null && u != null) attempts = attemptRepo.findByStatusAndPaperAndUser(AttemptStatus.SUBMITTED, p, u);
        else if (p != null) attempts = attemptRepo.findByStatusAndPaper(AttemptStatus.SUBMITTED, p);
        else if (u != null) attempts = attemptRepo.findByStatusAndUser(AttemptStatus.SUBMITTED, u);
        else attempts = attemptRepo.findByStatus(AttemptStatus.SUBMITTED);
        attempts.sort((a, b) -> {
            double sa = a.getScoreTotal() != null ? a.getScoreTotal() : 0.0;
            double sb = b.getScoreTotal() != null ? b.getScoreTotal() : 0.0;
            return Double.compare(sb, sa);
        });
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet("Scores");

            org.apache.poi.ss.usermodel.Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerFont.setFontHeightInPoints((short)11);
            org.apache.poi.ss.usermodel.CellStyle headerStyle = wb.createCellStyle();
            headerStyle.setFont(headerFont);
            headerStyle.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER);
            headerStyle.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.CENTER);
            headerStyle.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
            headerStyle.setBorderTop(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            headerStyle.setBorderBottom(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            headerStyle.setBorderLeft(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            headerStyle.setBorderRight(org.apache.poi.ss.usermodel.BorderStyle.THIN);

            org.apache.poi.ss.usermodel.CellStyle dataStyle = wb.createCellStyle();
            dataStyle.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.LEFT);
            dataStyle.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.CENTER);
            dataStyle.setBorderTop(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            dataStyle.setBorderBottom(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            dataStyle.setBorderLeft(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            dataStyle.setBorderRight(org.apache.poi.ss.usermodel.BorderStyle.THIN);

            org.apache.poi.ss.usermodel.DataFormat fmt = wb.getCreationHelper().createDataFormat();
            org.apache.poi.ss.usermodel.CellStyle numStyle = wb.createCellStyle();
            numStyle.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.RIGHT);
            numStyle.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.CENTER);
            numStyle.setBorderTop(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            numStyle.setBorderBottom(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            numStyle.setBorderLeft(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            numStyle.setBorderRight(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            numStyle.setDataFormat(fmt.getFormat("0.0"));

            org.apache.poi.ss.usermodel.CellStyle intStyle = wb.createCellStyle();
            intStyle.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.RIGHT);
            intStyle.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.CENTER);
            intStyle.setBorderTop(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            intStyle.setBorderBottom(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            intStyle.setBorderLeft(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            intStyle.setBorderRight(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            intStyle.setDataFormat(fmt.getFormat("0"));

            org.apache.poi.ss.usermodel.CellStyle dateStyle = wb.createCellStyle();
            dateStyle.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER);
            dateStyle.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.CENTER);
            dateStyle.setBorderTop(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            dateStyle.setBorderBottom(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            dateStyle.setBorderLeft(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            dateStyle.setBorderRight(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            dateStyle.setDataFormat(fmt.getFormat("yyyy-mm-dd hh:mm:ss"));

            int r = 0;
            org.apache.poi.ss.usermodel.Row header = sheet.createRow(r++);
            header.setHeightInPoints(20f);
            org.apache.poi.ss.usermodel.Cell h0 = header.createCell(0);
            h0.setCellValue("\u63D0\u4EA4ID");
            h0.setCellStyle(headerStyle);
            org.apache.poi.ss.usermodel.Cell h1 = header.createCell(1);
            h1.setCellValue("\u7528\u6237ID");
            h1.setCellStyle(headerStyle);
            org.apache.poi.ss.usermodel.Cell h2 = header.createCell(2);
            h2.setCellValue("\u7528\u6237\u540D");
            h2.setCellStyle(headerStyle);
            org.apache.poi.ss.usermodel.Cell h3 = header.createCell(3);
            h3.setCellValue("\u663E\u793A\u540D");
            h3.setCellStyle(headerStyle);
            org.apache.poi.ss.usermodel.Cell h4 = header.createCell(4);
            h4.setCellValue("\u603B\u5206");
            h4.setCellStyle(headerStyle);
            org.apache.poi.ss.usermodel.Cell h5 = header.createCell(5);
            h5.setCellValue("\u6B63\u786E\u6570");
            h5.setCellStyle(headerStyle);
            org.apache.poi.ss.usermodel.Cell h6 = header.createCell(6);
            h6.setCellValue("\u7ED3\u675F\u65F6\u95F4");
            h6.setCellStyle(headerStyle);

            sheet.createFreezePane(0, 1);
            sheet.setDefaultColumnWidth(18);

            for (ExamAttempt a : attempts) {
                org.apache.poi.ss.usermodel.Row row = sheet.createRow(r++);
                org.apache.poi.ss.usermodel.Cell c0 = row.createCell(0);
                c0.setCellValue(a.getId());
                c0.setCellStyle(intStyle);
                org.apache.poi.ss.usermodel.Cell c1 = row.createCell(1);
                c1.setCellValue(a.getUser() != null && a.getUser().getId() != null ? a.getUser().getId() : 0);
                c1.setCellStyle(intStyle);
                org.apache.poi.ss.usermodel.Cell c2 = row.createCell(2);
                c2.setCellValue(a.getUser() != null ? a.getUser().getUsername() : "");
                c2.setCellStyle(dataStyle);
                org.apache.poi.ss.usermodel.Cell c3 = row.createCell(3);
                c3.setCellValue(a.getUser() != null ? a.getUser().getDisplayName() : "");
                c3.setCellStyle(dataStyle);
                org.apache.poi.ss.usermodel.Cell c4 = row.createCell(4);
                c4.setCellValue(a.getScoreTotal() != null ? a.getScoreTotal() : 0.0);
                c4.setCellStyle(numStyle);
                org.apache.poi.ss.usermodel.Cell c5 = row.createCell(5);
                c5.setCellValue(a.getCorrectCount() != null ? a.getCorrectCount() : 0);
                c5.setCellStyle(intStyle);
                org.apache.poi.ss.usermodel.Cell c6 = row.createCell(6);
                c6.setCellValue(a.getEndTime() != null ? java.sql.Timestamp.valueOf(a.getEndTime()) : null);
                c6.setCellStyle(dateStyle);
            }
            for (int i = 0; i <= 6; i++) sheet.autoSizeColumn(i);
            wb.write(out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new BusinessException("failed to export: " + e.getMessage());
        }
    }

    // ================= 下载导入模板 =================

    /** 用户导入模板（xlsx）：列顺序须与 importUsers 读取的 0-3 列一致。 */
    public byte[] buildUserImportTemplate() {
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet("用户导入模板");
            String[] headers = {"username", "displayName", "email", "password"};
            org.apache.poi.ss.usermodel.CellStyle headStyle = wb.createCellStyle();
            org.apache.poi.ss.usermodel.Font f = wb.createFont();
            f.setBold(true);
            headStyle.setFont(f);
            org.apache.poi.ss.usermodel.CellStyle highlight = wb.createCellStyle();
            highlight.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.LIGHT_YELLOW.getIndex());
            highlight.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);

            org.apache.poi.ss.usermodel.Row head = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                org.apache.poi.ss.usermodel.Cell c = head.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(headStyle);
            }
            // 示例行：导出时请删除
            org.apache.poi.ss.usermodel.Row ex = sheet.createRow(1);
            String[] vals = {"example_user_01", "示例用户", "user@example.com", "123456"};
            for (int i = 0; i < vals.length; i++) {
                org.apache.poi.ss.usermodel.Cell c = ex.createCell(i);
                c.setCellValue(vals[i]);
                c.setCellStyle(highlight);
            }
            sheet.createFreezePane(0, 1);
            for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);
            wb.write(out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new BusinessException("failed to build template: " + e.getMessage());
        }
    }

    private static final String PAPER_IMPORT_TEMPLATE =
            "# 示例试卷（标题）\n" +
            "第一行以下为正文说明，可写任意简介。\n\n" +
            "## Q1 [SINGLE] score=2\n" +
            "题干：用于定义类的关键字是？\n" +
            "- A. class\n" +
            "- B. func\n" +
            "- C. def\n" +
            "Answer: A\n\n" +
            "## Q2 [MULTIPLE] score=3\n" +
            "题干：以下哪些是正确的？（多选）\n" +
            "- A. 选项一\n" +
            "- B. 选项二\n" +
            "- C. 选项三\n" +
            "- D. 选项四\n" +
            "Answer: A,B,C\n\n" +
            "## Q3 [TRUE_FALSE] score=1\n" +
            "题干：Java 是一种面向对象的编程语言。\n" +
            "- TRUE\n" +
            "- FALSE\n" +
            "Answer: TRUE\n\n" +
            "## Q4 [FILL] score=2\n" +
            "题干：Java 中定义常量使用关键字 ___。\n" +
            "Answer: final\n\n" +
            "## Q5 [CODE] score=10\n" +
            "题干：请实现一个返回两个整数之和的方法。\n" +
            "```java\n" +
            "public int add(int a, int b) {\n" +
            "    // TODO\n" +
            "}\n" +
            "```\n" +
            "Answer: ```java\npublic int add(int a, int b) { return a + b; }\n```\n";

    public byte[] buildPaperImportTemplate() {
        return PAPER_IMPORT_TEMPLATE.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 管理端"试卷详情 + 标准答案"视图数据（不同于用户端 /api/papers/{id}，后者不返回任何答案）。
     * 返回：{paper, questions:[{id,type,content,score,orderIndex,correctOptionIds,fillKeys,expectedCode}], options:[{id,questionId,label,content}]}
     * 说明：选项内容统一放 options（含与 questionId 关联）；正确选项 id 与填空/代码参考答案放 questions，
     * 便于前端只读渲染"正确答案"，避免任何作答数据。
     */
    public java.util.Map<String, Object> paperAnswersForAdmin(Long paperId) {
        ExamPaper p = paperRepo.findById(paperId).orElseThrow(() -> new BusinessException(404, "exam paper not found"));
        java.util.List<ExamQuestion> qs = questionRepo.findByPaperOrderByOrderIndexAsc(p);
        java.util.List<Long> qids = new java.util.ArrayList<>();
        for (ExamQuestion q : qs) qids.add(q.getId());

        // ---- 批量取数（每类仅 1 次 SQL，消除原循环内 N+1）----
        // 选项（全部），按题目分组
        java.util.Map<Long, java.util.List<QuestionOption>> optionsByQuestion =
                optionRepo.findByQuestionIdIn(qids).stream()
                        .collect(java.util.stream.Collectors.groupingBy(o -> o.getQuestion().getId()));
        // 填空参考答案 / 代码参考答案
        java.util.Map<Long, java.util.List<QuestionTextKey>> fillKeysByQuestion =
                textKeyRepo.findByQuestionIdInAndKeyType(qids, com.exam.model.entity.KeyType.FILL).stream()
                        .collect(java.util.stream.Collectors.groupingBy(t -> t.getQuestion().getId()));
        java.util.Map<Long, java.util.List<QuestionTextKey>> codeKeysByQuestion =
                textKeyRepo.findByQuestionIdInAndKeyType(qids, com.exam.model.entity.KeyType.CODE).stream()
                        .collect(java.util.stream.Collectors.groupingBy(t -> t.getQuestion().getId()));

        java.util.List<java.util.Map<String, Object>> questions = new java.util.ArrayList<>();
        java.util.List<java.util.Map<String, Object>> options = new java.util.ArrayList<>();
        for (ExamQuestion q : qs) {
            java.util.Map<String, Object> qm = new java.util.LinkedHashMap<>();
            qm.put("id", q.getId());
            qm.put("type", q.getType());
            qm.put("content", q.getContent());
            qm.put("score", q.getScore());
            qm.put("orderIndex", q.getOrderIndex());
            if (q.getType() == QuestionType.SINGLE || q.getType() == QuestionType.MULTIPLE || q.getType() == QuestionType.TRUE_FALSE) {
                java.util.List<Long> correctIds = new java.util.ArrayList<>();
                java.util.List<QuestionOption> qOptions = optionsByQuestion.getOrDefault(q.getId(), java.util.Collections.emptyList());
                for (QuestionOption o : qOptions) {
                    options.add(java.util.Map.of(
                            "id", o.getId(),
                            "questionId", q.getId(),
                            "label", o.getLabel(),
                            "content", o.getContent()
                    ));
                    if (Boolean.TRUE.equals(o.getIsCorrect())) correctIds.add(o.getId());
                }
                qm.put("correctOptionIds", correctIds);
            } else if (q.getType() == QuestionType.FILL_BLANK) {
                java.util.List<QuestionTextKey> keys = fillKeysByQuestion.getOrDefault(q.getId(), java.util.Collections.emptyList());
                keys.sort(java.util.Comparator.comparingInt(t -> t.getKeyIndex() != null ? t.getKeyIndex() : 0));
                java.util.List<String> fillKeys = new java.util.ArrayList<>();
                for (QuestionTextKey t : keys) fillKeys.add(t.getTextValue());
                qm.put("fillKeys", fillKeys);
            } else if (q.getType() == QuestionType.CODE) {
                java.util.List<QuestionTextKey> refs = codeKeysByQuestion.getOrDefault(q.getId(), java.util.Collections.emptyList());
                qm.put("expectedCode", refs.isEmpty() ? null : refs.get(0).getTextValue());
            }
            questions.add(qm);
        }
        java.util.Map<String, Object> resp = new java.util.LinkedHashMap<>();
        resp.put("paper", p);
        resp.put("questions", questions);
        resp.put("options", options);
        return resp;
    }

    @Transactional
    public void deleteAttempt(Long attemptId) {
        ExamAttempt a = attemptRepo.findById(attemptId).orElseThrow(() -> new BusinessException(404, "attempt not found"));
        answerOptionRepo.deleteByAttempt(a);
        answerTextRepo.deleteByAttempt(a);
        answerRepo.deleteByAttempt(a);
        attemptRepo.delete(a);
    }

    @org.springframework.transaction.annotation.Transactional
    public com.exam.model.entity.User createUser(java.util.Map<String, String> body) {
        String username = body != null ? body.get("username") : null;
        String displayName = body != null ? body.get("displayName") : null;
        String email = body != null ? body.get("email") : null;
        String password = body != null ? body.get("password") : null;
        String roleStr = body != null ? body.get("role") : null;
        if (username == null || username.isBlank()) throw new BusinessException(422, "invalid username");
        if (userRepo.existsByUsername(username)) throw new BusinessException(409, "username exists");
        User u = new User();
        u.setUsername(username);
        u.setDisplayName(displayName != null && !displayName.isBlank() ? displayName : username);
        u.setEmail(email);
        com.exam.model.entity.Role role = com.exam.model.entity.Role.USER;
        if (roleStr != null && !roleStr.isBlank()) {
            try {
                role = com.exam.model.entity.Role.valueOf(roleStr.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                // 与 pageUsersByRole 的非法角色处理保持一致，返回业务 422 而非 500
                throw new BusinessException(422, "invalid role");
            }
        }
        u.setRole(role);
        String raw = (password != null && !password.isBlank()) ? password : (username + "123");
        u.setPasswordHash(passwordEncoder.encode(raw));
        u.setStatus(com.exam.model.entity.UserStatus.ACTIVE);
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        return userRepo.save(u);
    }

    @org.springframework.transaction.annotation.Transactional
    public java.util.Map<String, Object> deleteUsers(java.util.List<Long> ids) {
        if (ids == null || ids.isEmpty()) throw new BusinessException(422, "empty ids");
        java.util.List<Long> deleted = new java.util.ArrayList<>();
        java.util.List<Long> failed = new java.util.ArrayList<>();
        for (Long id : ids) {
            User u = userRepo.findById(id).orElse(null);
            if (u == null) { failed.add(id); continue; }
            java.util.List<ExamAttempt> ats = attemptRepo.findByUser(u);
            if (ats != null && !ats.isEmpty()) { failed.add(id); continue; }
            userRepo.delete(u);
            deleted.add(id);
        }
        return java.util.Map.of("deleted", deleted.size(), "failed", failed.size(), "failedIds", failed);
    }

    @Transactional
    public java.util.Map<String, Object> resetAllPasswords(String newPassword) {
        if (newPassword == null || newPassword.isBlank()) {
            throw new BusinessException(422, "invalid new password");
        }
        java.util.List<User> users = userRepo.findAll();
        int updated = 0;
        String encoded = passwordEncoder.encode(newPassword);
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        for (User u : users) {
            u.setPasswordHash(encoded);
            u.setUpdatedAt(now);
            userRepo.save(u);
            updated++;
        }
        return java.util.Map.of("updated", updated);
    }

    @Transactional
    public java.util.Map<String, Object> resetUserPassword(Long userId, String newPassword) {
        if (newPassword == null || newPassword.isBlank()) {
            throw new BusinessException(422, "invalid new password");
        }
        User user = userRepo.findById(userId).orElseThrow(() -> new BusinessException(404, "user not found"));
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setUpdatedAt(java.time.LocalDateTime.now());
        userRepo.save(user);
        return java.util.Map.of("updated", 1, "userId", userId);
    }

    @org.springframework.transaction.annotation.Transactional
    public java.util.Map<String, Object> gradeCodeAnswers(Long attemptId, java.util.List<com.exam.model.dto.request.AdminCodeGradeRequest.GradeItem> grades) {
        ExamAttempt attempt = attemptRepo.findById(attemptId).orElseThrow(() -> new BusinessException(404, "attempt not found"));
        java.util.Map<Long, Double> byQ = new java.util.HashMap<>();
        for (com.exam.model.dto.request.AdminCodeGradeRequest.GradeItem gi : grades) {
            byQ.put(gi.getQuestionId(), gi.getAwardedScore() != null ? gi.getAwardedScore() : 0.0);
        }
        java.util.List<ExamAnswer> answers = answerRepo.findByAttempt(attempt);
        for (ExamAnswer a : answers) {
            if (a.getQuestion().getType() == QuestionType.CODE) {
                Double sc = byQ.getOrDefault(a.getQuestion().getId(), 0.0);
                a.setAwardedScore(sc);
                boolean correct = sc != null && sc >= a.getQuestion().getScore();
                a.setIsCorrect(correct);
                a.setAnsweredAt(java.time.LocalDateTime.now());
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
        return java.util.Map.of("attemptId", attempt.getId(), "scoreTotal", attempt.getScoreTotal(), "correctCount", attempt.getCorrectCount());
    }
}
