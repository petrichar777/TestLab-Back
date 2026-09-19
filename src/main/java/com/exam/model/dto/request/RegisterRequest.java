package com.exam.model.dto.request;

import lombok.Data;

@Data
public class RegisterRequest {
    private String username;
    private String password;
    private String displayName;
    private String email;
}