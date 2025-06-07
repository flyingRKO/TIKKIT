package com.tikkit.api.user.dto;

import com.tikkit.api.user.entity.User;

public record UserResponse(
        String email,
        String name,
        String phone
) {
    public static UserResponse from(User user) {
        return new UserResponse(user.getEmail(), user.getName(), user.getPhone());
    }
}
