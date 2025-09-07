package com.tikkit.api.user.controller;

import com.tikkit.api.global.exception.ErrorCode;
import com.tikkit.api.global.response.ApiResponse;
import com.tikkit.api.user.dto.UserRegisterRequest;
import com.tikkit.api.user.dto.UserResponse;
import com.tikkit.api.user.dto.UserEmailCheckResponse;
import com.tikkit.api.user.entity.User;
import com.tikkit.api.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<UserResponse>> register(@RequestBody @Valid UserRegisterRequest request){
        User user = User.of(
                request.email(),
                request.password(),
                request.name(),
                request.phone()
        );
        User savedUser = userService.register(user);
        return ResponseEntity.ok(ApiResponse.success(UserResponse.from(savedUser)));
    }

    @GetMapping("/check-email")
    public ResponseEntity<ApiResponse<UserEmailCheckResponse>> checkEmailDuplicate(@RequestParam String email) {
        // ⚠️ 형식/필수값 검증은 UserValidator 또는 Service에서 수행(예외 발생 시 GlobalExceptionHandler가 처리)
        boolean exists = userService.isEmailDuplicated(email);

        if (exists) {
            // 중복: 200 OK + success=false + ErrorCode (data=null)
            return ResponseEntity.ok(
                    ApiResponse.<UserEmailCheckResponse>fail(
                            ErrorCode.DUPLICATE_EMAIL.getCode(),
                            ErrorCode.DUPLICATE_EMAIL.getMessage()
                    )
            );
        }

        // 사용 가능: 200 OK + success=true + data(available=true)
        return ResponseEntity.ok(
                ApiResponse.success(new UserEmailCheckResponse(true))
        );
    }
}
