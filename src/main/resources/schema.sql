CREATE TABLE IF NOT EXISTS users (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(64) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  role ENUM('ADMIN','USER') NOT NULL,
  display_name VARCHAR(128) NOT NULL,
  email VARCHAR(128) UNIQUE,
  status ENUM('ACTIVE','DISABLED') NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS exam_paper (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  code VARCHAR(64) UNIQUE,
  title VARCHAR(255) NOT NULL,
  description TEXT,
  total_score DECIMAL(8,2) NOT NULL DEFAULT 0,
  is_published TINYINT(1) NOT NULL DEFAULT 0,
  visible_from DATETIME,
  visible_to DATETIME,
  created_by BIGINT,
  imported_at DATETIME,
  source_md_name VARCHAR(255),
  source_md_hash CHAR(64),
  version INT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_exam_paper_created_by FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS exam_question (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  paper_id BIGINT NOT NULL,
  type ENUM('SINGLE','MULTIPLE','TRUE_FALSE') NOT NULL,
  content TEXT NOT NULL,
  score DECIMAL(6,2) NOT NULL DEFAULT 1.00,
  order_index INT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_exam_question_paper FOREIGN KEY (paper_id) REFERENCES exam_paper(id) ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT uq_exam_question_order UNIQUE (paper_id, order_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS question_option (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  question_id BIGINT NOT NULL,
  label VARCHAR(16) NOT NULL,
  content TEXT NOT NULL,
  is_correct TINYINT(1) NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_question_option_question FOREIGN KEY (question_id) REFERENCES exam_question(id) ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT uq_question_option_label UNIQUE (question_id, label)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_question_option_question_correct ON question_option (question_id, is_correct);

CREATE TABLE IF NOT EXISTS exam_attempt (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  paper_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  start_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  end_time DATETIME,
  status ENUM('IN_PROGRESS','SUBMITTED','CANCELLED') NOT NULL DEFAULT 'IN_PROGRESS',
  score_total DECIMAL(8,2) NOT NULL DEFAULT 0,
  correct_count INT NOT NULL DEFAULT 0,
  question_count INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_exam_attempt_paper FOREIGN KEY (paper_id) REFERENCES exam_paper(id) ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT fk_exam_attempt_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_exam_attempt_user_paper_status ON exam_attempt (user_id, paper_id, status);

-- 覆盖查询/排序索引（对应默认排序，避免 filesort）：
-- 用户端"我的考试"：WHERE status=? AND user_id=? ORDER BY end_time DESC, id DESC
CREATE INDEX idx_exam_attempt_user_status_end ON exam_attempt (user_id, status, end_time, id);
-- 管理端成绩全量列表：WHERE status=? ORDER BY score_total DESC, id DESC
CREATE INDEX idx_exam_attempt_status_score ON exam_attempt (status, score_total, id);
-- 管理端按试卷筛选：WHERE status=? AND paper_id=? ORDER BY score_total DESC, id DESC
CREATE INDEX idx_exam_attempt_paper_status_score ON exam_attempt (paper_id, status, score_total, id);

CREATE TABLE IF NOT EXISTS exam_answer (
  attempt_id BIGINT NOT NULL,
  question_id BIGINT NOT NULL,
  awarded_score DECIMAL(6,2) NOT NULL DEFAULT 0,
  is_correct TINYINT(1) NOT NULL DEFAULT 0,
  answered_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (attempt_id, question_id),
  CONSTRAINT fk_exam_answer_attempt FOREIGN KEY (attempt_id) REFERENCES exam_attempt(id) ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT fk_exam_answer_question FOREIGN KEY (question_id) REFERENCES exam_question(id) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS exam_answer_option (
  attempt_id BIGINT NOT NULL,
  question_id BIGINT NOT NULL,
  option_id BIGINT NOT NULL,
  selected_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (attempt_id, question_id, option_id),
  CONSTRAINT fk_answer_option_answer FOREIGN KEY (attempt_id, question_id) REFERENCES exam_answer(attempt_id, question_id) ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT fk_answer_option_option FOREIGN KEY (option_id) REFERENCES question_option(id) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- question_text_key 参考答案查询：WHERE question_id IN (?) AND key_type=? （表由历史迁移创建，此处仅补索引）
CREATE INDEX idx_question_text_key_question_type ON question_text_key (question_id, key_type, key_index);
-- AI 配置（管理端设置页）：单一配置行，保存用户自填的供应商/apiKey/模型/baseUrl
CREATE TABLE IF NOT EXISTS ai_config (
  id BIGINT PRIMARY KEY,
  provider VARCHAR(64) NOT NULL DEFAULT 'deepseek',
  model VARCHAR(128) NOT NULL DEFAULT 'deepseek-chat',
  api_key VARCHAR(512) DEFAULT NULL,
  base_url VARCHAR(255) DEFAULT NULL,
  updated_by VARCHAR(64),
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO ai_config (id, provider, model, api_key, base_url)
SELECT 1, 'deepseek', 'deepseek-chat', NULL, NULL
WHERE NOT EXISTS (SELECT 1 FROM ai_config WHERE id = 1);

-- AI 供应商（后台可维护模型目录）
CREATE TABLE IF NOT EXISTS ai_provider (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  code VARCHAR(64) NOT NULL UNIQUE,
  name VARCHAR(128) NOT NULL,
  base_url VARCHAR(255) DEFAULT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- AI 模型（隶属于某供应商）
CREATE TABLE IF NOT EXISTS ai_model (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  provider_id BIGINT NOT NULL,
  name VARCHAR(128) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  hot TINYINT(1) NOT NULL DEFAULT 0,
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_ai_model_provider_name (provider_id, name),
  CONSTRAINT fk_ai_model_provider FOREIGN KEY (provider_id) REFERENCES ai_provider(id) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
