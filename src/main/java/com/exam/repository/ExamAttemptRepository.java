package com.exam.repository;

import com.exam.model.entity.ExamAttempt;
import com.exam.model.entity.ExamPaper;
import com.exam.model.entity.User;
import com.exam.model.entity.AttemptStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExamAttemptRepository extends JpaRepository<ExamAttempt, Long> {
    Page<ExamAttempt> findByStatusAndPaperAndUser(AttemptStatus status, ExamPaper paper, User user, Pageable pageable);
    Page<ExamAttempt> findByStatus(AttemptStatus status, Pageable pageable);
    Page<ExamAttempt> findByStatusAndPaper(AttemptStatus status, ExamPaper paper, Pageable pageable);
    Page<ExamAttempt> findByStatusAndUser(AttemptStatus status, User user, Pageable pageable);
    java.util.List<ExamAttempt> findByStatus(AttemptStatus status);
    java.util.List<ExamAttempt> findByStatusAndPaper(AttemptStatus status, ExamPaper paper);
    java.util.List<ExamAttempt> findByStatusAndUser(AttemptStatus status, User user);
    java.util.List<ExamAttempt> findByStatusAndPaperAndUser(AttemptStatus status, ExamPaper paper, User user);
    java.util.    List<ExamAttempt> findByUser(User user);

    // 成绩列表：按用户真实姓名 / 用户名模糊搜索（可选按试卷）。paperId 为 null 表示不过滤试卷。
    @Query("select a from ExamAttempt a where a.status = :status " +
            "and (:paperId is null or a.paper.id = :paperId) " +
            "and (:name is null or lower(a.user.displayName) like lower(concat('%', :name, '%')) " +
            "or lower(a.user.username) like lower(concat('%', :name, '%')))")
    Page<ExamAttempt> searchByName(@Param("status") AttemptStatus status, @Param("paperId") Long paperId,
                                   @Param("name") String name, Pageable pageable);

    // 成绩列表：可选按试卷 / 姓名 / 批改状态（graded: true 已批改, false 未批改, null 全部）筛选。
    // coalesce 把老数据 NULL 的 code_graded 视为 false（未批改）。
    @Query("select a from ExamAttempt a where a.status = :status " +
            "and (:paperId is null or a.paper.id = :paperId) " +
            "and (:name is null or lower(a.user.displayName) like lower(concat('%', :name, '%')) " +
            "or lower(a.user.username) like lower(concat('%', :name, '%'))) " +
            "and (:graded is null or coalesce(a.codeGraded, false) = :graded)")
    Page<ExamAttempt> searchScores(@Param("status") AttemptStatus status, @Param("paperId") Long paperId,
                                   @Param("name") String name, @Param("graded") Boolean graded, Pageable pageable);

    // 成绩统计：一次 SQL 分组聚合（避免全表捞实体 + 逐卷 N+1）。
    // paperId / userId 传 -1 表示不过滤该维度（调用方以 null -> -1 传入）。
    // 返回行结构：[paperId, total, minScore, maxScore, avgScore]
    @Query(value = "SELECT paper_id AS paperId, COUNT(*) AS total, MIN(score_total) AS minScore, " +
            "MAX(score_total) AS maxScore, AVG(score_total) AS avgScore " +
            "FROM exam_attempt WHERE status = 'SUBMITTED' " +
            "AND (:paperId = -1 OR paper_id = :paperId) " +
            "AND (:userId = -1 OR user_id = :userId) " +
            "GROUP BY paper_id", nativeQuery = true)
    java.util.List<Object[]> aggregateSubmittedStats(@Param("paperId") long paperId, @Param("userId") long userId);

    // 成绩统计（按姓名模糊筛选）：返回行结构同 aggregateSubmittedStats
    @Query(value = "SELECT a.paper_id AS paperId, COUNT(*) AS total, MIN(a.score_total) AS minScore, " +
            "MAX(a.score_total) AS maxScore, AVG(a.score_total) AS avgScore " +
            "FROM exam_attempt a JOIN users u ON a.user_id = u.id " +
            "WHERE a.status = 'SUBMITTED' " +
            "AND (:paperId = -1 OR a.paper_id = :paperId) " +
            "AND (:name IS NULL OR u.display_name LIKE CONCAT('%', :name, '%') OR u.username LIKE CONCAT('%', :name, '%')) " +
            "GROUP BY a.paper_id", nativeQuery = true)
    java.util.List<Object[]> aggregateSubmittedStatsByName(@Param("paperId") long paperId, @Param("name") String name);

    // 成绩统计（按姓名 / 批改状态筛选）：graded 为 null 表示不过滤；返回行结构同 aggregateSubmittedStats
    @Query(value = "SELECT a.paper_id AS paperId, COUNT(*) AS total, MIN(a.score_total) AS minScore, " +
            "MAX(a.score_total) AS maxScore, AVG(a.score_total) AS avgScore " +
            "FROM exam_attempt a JOIN users u ON a.user_id = u.id " +
            "WHERE a.status = 'SUBMITTED' " +
            "AND (:paperId = -1 OR a.paper_id = :paperId) " +
            "AND (:name IS NULL OR u.display_name LIKE CONCAT('%', :name, '%') OR u.username LIKE CONCAT('%', :name, '%')) " +
            "AND (:graded IS NULL OR COALESCE(a.code_graded, 0) = :graded) " +
            "GROUP BY a.paper_id", nativeQuery = true)
    java.util.List<Object[]> aggregateScores(@Param("paperId") long paperId, @Param("name") String name,
                                             @Param("graded") Boolean graded);
}
