package com.tikkit.api.domain.member.dto;

import com.tikkit.api.domain.member.entity.MemberRole;

public record MemberResponse(
        Long id,
        String email,
        String name,
        String phone,
        MemberRole role
) {
}
