package com.exam.repository;

import com.exam.model.entity.AiModel;
import com.exam.model.entity.AiProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiModelRepository extends JpaRepository<AiModel, Long> {
    List<AiModel> findByProviderOrderBySortOrderAscIdAsc(AiProvider provider);
    void deleteByProvider(AiProvider provider);
}