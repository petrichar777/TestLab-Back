package com.exam.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 管理端 AI 配置（设置页）：单一配置行，保存用户自填的供应商 / apiKey / 模型 / baseUrl。
 * apiKey 仅能写入，读取时由服务层脱敏返回。
 */
@Entity
@Table(name = "ai_config")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiConfig {

    @Id
    private Long id;

    @Column(name = "provider", nullable = false, length = 64)
    private String provider = "deepseek";

    @Column(name = "model", nullable = false, length = 128)
    private String model = "deepseek-chat";

    @JsonIgnore
    @Column(name = "api_key", length = 512)
    private String apiKey;

    @Column(name = "base_url", length = 255)
    private String baseUrl;

    @Column(name = "updated_by", length = 64)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}