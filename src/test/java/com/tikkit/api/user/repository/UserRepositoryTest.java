package com.tikkit.api.user.repository;

import static org.assertj.core.api.BDDAssertions.then;
import static org.assertj.core.api.BDDAssertions.thenThrownBy;
import com.tikkit.api.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import support.TestContainersConfig;

@DataJpaTest
@ActiveProfiles("test")
@Import(TestContainersConfig.class)  // ← 컨테이너 자동 연결
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE) // H2로 대체 금지
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class UserRepositoryTest {

    @Autowired private UserRepository repository;
    @Autowired private TestEntityManager em;

    private static User newUser(String email) {
        // 레포지토리 테스트는 쿼리/제약만 관심사. 인코딩 여부는 무관.
        return User.of(email, "{noop}pw", "홍길동", "01000000000");
    }

    @BeforeEach
    void setUp() {
        em.persistAndFlush(newUser("seed@tikkit.com"));
    }

    @Nested
    @DisplayName("existsByEmail")
    class ExistsByEmail {

        @Test
        @DisplayName("이메일이 존재하지 않으면 false 를 반환한다")
        void existsByEmail_returnsFalse_whenEmailNotFound() {
            boolean exists = repository.existsByEmail("none@tikkit.com");
            then(exists).isFalse();
        }

        @Test
        @DisplayName("이메일이 존재하면 true 를 반환한다")
        void existsByEmail_returnsTrue_whenEmailExists() {
            boolean exists = repository.existsByEmail("seed@tikkit.com");
            then(exists).isTrue();
        }
    }

    @Nested
    @DisplayName("CRUD 기본 동작")
    class CrudSmoke {

        @Test
        @DisplayName("save 이후 findById 로 조회할 수 있다")
        void saveAndFindById_returnsPersistedEntity() {
            var saved = repository.saveAndFlush(newUser("a@tikkit.com"));
            var found = em.find(User.class, saved.getId());
            then(found).isNotNull();
            then(found.getEmail()).isEqualTo("a@tikkit.com");
        }
    }

    @Nested
    @DisplayName("제약 조건")
    class Constraints {

        @Test
        @DisplayName("email 에 unique 제약이 있으면 중복 저장 시 예외가 발생한다")
        void saveAndFlush_throwsException_whenEmailUniqueConstraintViolated() {
            // ⚠️ User.email에 실제 unique 제약(@Column(unique = true) 또는 DDL 인덱스)이 있어야 통과합니다.
            thenThrownBy(() -> repository.saveAndFlush(newUser("seed@tikkit.com")))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }
}

