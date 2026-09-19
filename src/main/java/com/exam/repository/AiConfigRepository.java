package com.exam.repository;

import com.exam.model.entity.AiConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiConfigRepository extends JpaRepository<AiConfig, Long> {
}