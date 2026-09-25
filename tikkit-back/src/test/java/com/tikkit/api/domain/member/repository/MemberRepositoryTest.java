package com.tikkit.api.domain.member.repository;

import com.tikkit.api.domain.member.entity.Member;
import com.tikkit.api.domain.member.entity.MemberRole;
import com.tikkit.api.support.AbstractContainerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class MemberRepositoryTest extends AbstractContainerTest {

    @Autowired
    private MemberRepository memberRepository;

    @Test
    @DisplayName("대소문자만 다른 이메일은 정규화 후 중복으로 처리되어 저장에 실패한다")
    void 이메일_대소문자_무시_유니크_제약() {
        // given
        memberRepository.saveAndFlush(member("user@tikkit.com"));

        // when & then
        assertThatThrownBy(() -> memberRepository.saveAndFlush(member("User@Tikkit.com")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Member member(String email) {
        return Member.builder()
                .email(email)
                .password("encoded-password")
                .name("홍길동")
                .phone("010-1111-2222")
                .role(MemberRole.USER)
                .build();
    }
}
