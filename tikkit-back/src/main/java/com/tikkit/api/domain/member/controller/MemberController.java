package com.tikkit.api.domain.member.controller;

import com.tikkit.api.common.response.ApiResponse;
import com.tikkit.api.domain.member.dto.MemberResponse;
import com.tikkit.api.domain.member.service.MemberService;
import com.tikkit.api.security.MemberDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Member", description = "내 정보")
@RestController
@RequestMapping("/api/v1/members")
@RequiredArgsConstructor
public class MemberController {

    private final MemberService memberService;

    @Operation(summary = "내 정보 조회")
    @GetMapping("/me")
    public ApiResponse<MemberResponse> me(@AuthenticationPrincipal MemberDetails memberDetails) {
        return ApiResponse.success(memberService.getMe(memberDetails.getMemberId()));
    }
}
