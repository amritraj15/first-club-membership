package com.firstclub.membership.web;

import com.firstclub.membership.service.AdminApiKeyGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Enforces the admin API boundary uniformly for every route under {@code /api/admin/**}. */
@Component
public class AdminApiKeyInterceptor implements HandlerInterceptor {

    private final AdminApiKeyGuard adminApiKeyGuard;

    public AdminApiKeyInterceptor(AdminApiKeyGuard adminApiKeyGuard) {
        this.adminApiKeyGuard = adminApiKeyGuard;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        adminApiKeyGuard.verify(request.getHeader("X-Admin-Api-Key"));
        return true;
    }
}
