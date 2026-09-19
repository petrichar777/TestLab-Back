package com.exam.security;

import com.exam.exception.BusinessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 安全上下文工具：从 SecurityContextHolder 读取当前登录用户名/角色。
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    /**
     * 读取当前登录用户名；未认证/匿名时抛出 401 业务异常，由全局异常处理器统一返回。
     */
    public static String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
            throw new BusinessException(401, "unauthorized");
        }
        return auth.getName();
    }

    /**
     * 读取当前登录用户名；未认证/匿名时返回 null（不抛异常）。
     * 用于允许匿名访问的路径上做可选的用户级判断。
     */
    public static String currentUsernameOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth.getName().isBlank()
                || "anonymousUser".equals(auth.getName())) {
            return null;
        }
        return auth.getName();
    }

    /**
     * 判断当前用户是否具备指定角色（role 不带 ROLE_ 前缀，如 "ADMIN"）。
     * 未认证/匿名或无该角色时返回 false（不抛异常）。
     */
    public static boolean hasRole(String role) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) {
            return false;
        }
        String expected = "ROLE_" + role;
        return auth.getAuthorities().stream()
                .anyMatch(a -> a != null && expected.equalsIgnoreCase(a.getAuthority()));
    }
}
