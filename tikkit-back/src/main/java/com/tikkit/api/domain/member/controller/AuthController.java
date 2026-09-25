package com.tikkit.api.domain.member.controller;

import com.tikkit.api.common.response.ApiResponse;
import com.tikkit.api.domain.member.dto.LoginRequest;
import com.tikkit.api.domain.member.dto.LoginResponse;
import com.tikkit.api.domain.member.dto.SignupRequest;
import com.tikkit.api.domain.member.dto.SignupResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth", description = "회원가입·로그인")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    @Operation(summary = "회원가입")
    @PostMapping("/signup")
    public ApiResponse<SignupResponse> signup(@RequestBody @Valid SignupRequest request) {
        // TODO(Task 008): Spring Security 도입 후 실제 회원 생성 로직 연결
        return ApiResponse.success(new SignupResponse(1L, request.email(), request.name()));
    }

    @Operation(summary = "로그인")
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@RequestBody @Valid LoginRequest request) {
        // TODO(Task 008): Spring Security + JJWT 도입 후 실제 인증 로직 연결
        return ApiResponse.success(new LoginResponse("dummy-access-token", "Bearer", 3600L));
    }
}
