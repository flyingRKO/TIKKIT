package com.tikkit.api.domain.member.service;

import com.tikkit.api.common.exception.BusinessException;
import com.tikkit.api.common.exception.ErrorCode;
import com.tikkit.api.domain.member.dto.SignupRequest;
import com.tikkit.api.domain.member.dto.SignupResponse;
import com.tikkit.api.domain.member.entity.Member;
import com.tikkit.api.domain.member.entity.MemberRole;
import com.tikkit.api.domain.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public SignupResponse signup(SignupRequest request) {
        String email = request.email().toLowerCase();
        if (memberRepository.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }

        Member member = Member.builder()
                .email(email)
                .password(passwordEncoder.encode(request.password()))
                .name(request.name())
                .phone(request.phone())
                .role(MemberRole.USER)
                .build();

        try {
            memberRepository.saveAndFlush(member);
        } catch (DataIntegrityViolationException e) {
            // existsByEmail 체크와 저장 사이에 동시 가입 요청이 끼어드는 레이스는
            // DB 유니크 제약(uk_members_email)이 최종 방어선이 되어 여기서 잡힌다.
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }

        return new SignupResponse(member.getId(), member.getEmail(), member.getName());
    }
}
