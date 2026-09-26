package com.tikkit.api.domain.member.repository;

import com.tikkit.api.domain.member.entity.Member;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long> {

    // email은 DB(uk_members_email)와 Member 엔티티 양쪽에서 소문자로 정규화해 저장하므로,
    // 조회할 때도 호출부에서 email.toLowerCase()로 넘겨야 한다.
    boolean existsByEmail(String email);

    Optional<Member> findByEmail(String email);
}
