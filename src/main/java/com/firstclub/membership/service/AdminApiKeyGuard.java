package com.firstclub.membership.service;

import com.firstclub.membership.exception.ForbiddenException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * A minimal, closed-by-default boundary for the assignment's runtime-admin API. Configure
 * MEMBERSHIP_ADMIN_API_KEY in the deployment environment, then pass it as X-Admin-Api-Key.
 * A production system should replace this with the platform's normal identity/role mechanism.
 */
@Component
public class AdminApiKeyGuard {

    private final byte[] expectedKey;

    public AdminApiKeyGuard(@Value("${membership.admin.api-key:}") String expectedKey) {
        this.expectedKey = expectedKey.getBytes(StandardCharsets.UTF_8);
    }

    public void verify(String suppliedKey) {
        byte[] supplied = suppliedKey == null ? new byte[0] : suppliedKey.getBytes(StandardCharsets.UTF_8);
        if (expectedKey.length == 0 || !MessageDigest.isEqual(expectedKey, supplied)) {
            throw new ForbiddenException("A valid X-Admin-Api-Key is required for benefit administration");
        }
    }
}
