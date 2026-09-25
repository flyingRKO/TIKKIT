package com.tikkit.api.domain.member.dto;

public record SignupResponse(
        Long id,
        String email,
        String name
) {
}
