package com.exam.config;

import com.exam.service.JwtService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * JWT 认证过滤器：从 Authorization: Bearer <token> 解析 token，
 * 校验通过后向 SecurityContext 写入认证信息；解析失败或缺失时直接放行，
 * 由 Spring Security 的规则决定是否返回 401/403，不在此处抛异常污染请求链路。
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = header.substring(7).trim();
            if (StringUtils.hasText(token)) {
                try {
                    Claims claims = jwtService.parse(token);
                    String username = claims.getSubject();
                    Object roleObj = claims.get("role");
                    if (StringUtils.hasText(username)) {
                        List<GrantedAuthority> authorities = new ArrayList<>();
                        if (roleObj != null && StringUtils.hasText(roleObj.toString())) {
                            authorities.add(new SimpleGrantedAuthority("ROLE_" + roleObj));
                        }
                        UsernamePasswordAuthenticationToken authentication =
                                new UsernamePasswordAuthenticationToken(username, null, authorities);
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    }
                } catch (Exception ignored) {
                    // 解析失败（token 过期、签名错误、格式非法等）时不设置认证，
                    // 交由后续 Security 规则统一返回 401/403。
                }
            }
        }
        filterChain.doFilter(request, response);
    }
}
