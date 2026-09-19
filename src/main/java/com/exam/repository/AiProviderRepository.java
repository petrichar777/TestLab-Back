package com.exam.repository;

import com.exam.model.entity.AiProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiProviderRepository extends JpaRepository<AiProvider, Long> {
    Optional<AiProvider> findByCode(String code);
    List<AiProvider> findAllByOrderBySortOrderAscIdAsc();
    boolean existsByCode(String code);
}