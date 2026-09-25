package com.tikkit.api.domain.member.dto;

public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresIn
) {
}
