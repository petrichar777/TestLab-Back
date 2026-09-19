SET @db := DATABASE();
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'exam_paper' AND COLUMN_NAME = 'exam_duration_minutes');
SET @sql := IF(@exists = 0, 'ALTER TABLE exam_paper ADD COLUMN exam_duration_minutes INT NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @db := DATABASE();
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'exam_answer' AND COLUMN_NAME = 'ai_feedback');
SET @sql := IF(@exists = 0, 'ALTER TABLE exam_answer ADD COLUMN ai_feedback TEXT NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @db := DATABASE();
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'exam_attempt' AND COLUMN_NAME = 'code_graded');
SET @sql := IF(@exists = 0, 'ALTER TABLE exam_attempt ADD COLUMN code_graded TINYINT(1) NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
