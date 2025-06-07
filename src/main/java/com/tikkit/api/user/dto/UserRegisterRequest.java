package com.tikkit.api.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UserRegisterRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 10, max = 20) String password,
        @NotBlank @Size(max = 20) String name,
        @NotBlank String phone
) {
}
