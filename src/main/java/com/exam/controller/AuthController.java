package com.exam.controller;

import com.exam.model.dto.request.LoginRequest;
import com.exam.model.dto.request.RegisterRequest;
import com.exam.security.SecurityUtils;
import com.exam.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest req) {
        return ResponseEntity.ok(authService.login(req));
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody RegisterRequest req) {
        return ResponseEntity.ok(authService.register(req));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(
            @org.springframework.web.bind.annotation.RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return ResponseEntity.ok(java.util.Map.of("message", "logged out"));
    }

    @PostMapping("/change-password")
    public ResponseEntity<java.util.Map<String, Object>> changePassword(
            @RequestBody java.util.Map<String, String> body
    ) {
        String username = SecurityUtils.currentUsername();
        String current = body != null ? body.get("currentPassword") : null;
        String next = body != null ? body.get("newPassword") : null;
        return ResponseEntity.ok(authService.changePassword(username, current, next));
    }
}
