package com.exam.model.dto.request;

import lombok.Data;

@Data
public class SetGradingModeRequest {
    private String mode;
    private Integer promptVersion;
}