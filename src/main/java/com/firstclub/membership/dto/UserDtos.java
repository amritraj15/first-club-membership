package com.firstclub.membership.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public class UserDtos {

    private UserDtos() {
    }

    public record CreateUserRequest(
            @NotBlank String name,
            @NotBlank @Email String email,
            String cohort
    ) {
    }

    public record UserResponse(Long id, String name, String email, String cohort) {
    }
}
