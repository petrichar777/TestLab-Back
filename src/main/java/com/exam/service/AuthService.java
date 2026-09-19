package com.exam.service;

import com.exam.exception.BusinessException;
import com.exam.model.entity.Role;
import com.exam.model.entity.User;
import com.exam.model.entity.UserStatus;
import com.exam.model.dto.request.LoginRequest;
import com.exam.model.dto.request.RegisterRequest;
import com.exam.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

@Service
public class AuthService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public Map<String, Object> login(LoginRequest req) {
        User user = userRepository.findByUsername(req.getUsername())
                .orElseThrow(() -> new BusinessException("invalid username or password"));
        // 账号被禁用（status != ACTIVE）时不签发 token，用 403 明确区别于密码错误(422)，避免泄露账号状态细节给无凭据方
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(403, "account disabled");
        }
        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            throw new BusinessException("invalid username or password");
        }
        String token = jwtService.generateToken(user.getUsername(), Map.of("role", user.getRole().name()));
        return Map.of("token", token, "role", user.getRole().name(), "displayName", user.getDisplayName());
    }

    public Map<String, Object> register(RegisterRequest req) {
        if (userRepository.existsByUsername(req.getUsername())) {
            throw new BusinessException("username already exists");
        }
        User user = new User();
        user.setUsername(req.getUsername());
        user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        user.setRole(Role.USER);
        user.setDisplayName(req.getDisplayName());
        user.setEmail(req.getEmail());
        user.setStatus(UserStatus.ACTIVE);
        LocalDateTime now = LocalDateTime.now();
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userRepository.save(user);
        String token = jwtService.generateToken(user.getUsername(), Map.of("role", user.getRole().name()));
        return Map.of("token", token, "role", user.getRole().name(), "displayName", user.getDisplayName());
    }

    public Map<String, Object> changePassword(String username, String currentPassword, String newPassword) {
        if (username == null || username.isBlank()) {
            throw new BusinessException(401, "unauthorized");
        }
        if (currentPassword == null || newPassword == null || newPassword.isBlank()) {
            throw new BusinessException(422, "invalid request");
        }
        User user = userRepository.findByUsername(username).orElseThrow(() -> new BusinessException(404, "user not found"));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BusinessException(422, "current password incorrect");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
        return Map.of("message", "password changed");
    }
}