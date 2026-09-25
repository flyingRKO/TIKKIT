package com.tikkit.api.domain.member.controller;

import com.tikkit.api.common.response.ApiResponse;
import com.tikkit.api.domain.member.dto.MemberResponse;
import com.tikkit.api.domain.member.entity.MemberRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Member", description = "내 정보")
@RestController
@RequestMapping("/api/v1/members")
public class MemberController {

    @Operation(summary = "내 정보 조회")
    @GetMapping("/me")
    public ApiResponse<MemberResponse> me() {
        // TODO(Task 008): SecurityContext에서 로그인한 회원 조회 로직 연결
        return ApiResponse.success(new MemberResponse(1L, "user@tikkit.com", "김도현", "010-1234-5678", MemberRole.USER));
    }
}
