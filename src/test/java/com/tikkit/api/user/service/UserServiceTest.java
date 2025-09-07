package com.tikkit.api.user.service;

import com.tikkit.api.global.exception.ErrorCode;
import com.tikkit.api.global.exception.ValidationException;
import com.tikkit.api.user.entity.User;
import com.tikkit.api.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserService 회원가입 테스트")
class UserServiceTest {

    @Mock
    UserRepository userRepository;

    @Mock
    PasswordEncoder passwordEncoder;

    @InjectMocks
    UserServiceImpl userService;

    @Nested
    @DisplayName("정상 회원가입")
    class RegisterSuccess {
        @Test
        @DisplayName("회원가입이 정상적으로 수행된다")
        void registerSuccessfully() {
            // Given
            User user = User.of("test@email.com", "password1234", "이재훈", "01077779999");
            when(userRepository.existsByEmail(user.getEmail())).thenReturn(false);
            when(passwordEncoder.encode(user.getPassword())).thenReturn("encodedPassword");
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

            // When
            User savedUser = userService.register(user);

            // Then
            verify(userRepository).existsByEmail(user.getEmail());
            verify(passwordEncoder).encode(user.getPassword());
            verify(userRepository).save(userCaptor.capture());

            assertThat(savedUser).isNotNull();
            assertThat(userCaptor.getValue().getEmail()).isEqualTo(user.getEmail());
            assertThat(userCaptor.getValue().getPassword()).isEqualTo("encodedPassword");
            assertThat(userCaptor.getValue().getName()).isEqualTo(user.getName());
            assertThat(userCaptor.getValue().getPhone()).isEqualTo(user.getPhone());
        }
    }

    @Nested
    @DisplayName("이메일 검증")
    class EmailValidation {
        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"", " "})
        @DisplayName("이메일이 null, 빈값, 공백이면 실패한다")
        void failIfEmailIsNullOrBlank(String email) {
            // Given
            User user = User.of(email, "password1234", "이재훈", "01077779999");

            // When & Then
            ValidationException exception = assertThrows(ValidationException.class, () -> userService.register(user));
            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.EMAIL_REQUIRED);
            verify(userRepository, never()).save(any(User.class));
        }

        @ParameterizedTest
        @ValueSource(strings = {"invalid-email", "test@.com", "test@com", "test.com"})
        @DisplayName("이메일 형식이 올바르지 않으면 실패한다")
        void failIfEmailFormatIsInvalid(String email) {
            // Given
            User user = User.of(email, "password1234", "이재훈", "01012345678");

            // When & Then
            ValidationException exception = assertThrows(ValidationException.class, () -> userService.register(user));
            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_EMAIL_FORMAT);
            verify(userRepository, never()).save(any(User.class));
        }

        @Test
        @DisplayName("이미 가입된 이메일로 가입하면 실패한다")
        void failIfEmailAlreadyExists() {
            // Given
            String existingEmail = "existing@email.com";
            User user = User.of(existingEmail, "password1234", "이재훈", "01077779999");
            when(userRepository.existsByEmail(existingEmail)).thenReturn(true);

            // When & Then
            ValidationException exception = assertThrows(ValidationException.class, 
                () -> userService.register(user));
            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_EMAIL);
            verify(userRepository).existsByEmail(existingEmail);
            verify(userRepository, never()).save(any(User.class));
        }

        @Test
        @DisplayName("존재하지 않는 이메일로 가입하면 성공한다")
        void successIfEmailNotExists() {
            // Given
            String newEmail = "new@email.com";
            User user = User.of(newEmail, "password1234", "이재훈", "01077779999");
            when(userRepository.existsByEmail(newEmail)).thenReturn(false);
            when(passwordEncoder.encode(user.getPassword())).thenReturn("encodedPassword");
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // When
            User savedUser = userService.register(user);

            // Then
            verify(userRepository).existsByEmail(newEmail);
            verify(userRepository).save(any(User.class));
            assertThat(savedUser).isNotNull();
            assertThat(savedUser.getEmail()).isEqualTo(newEmail);
        }

        @Test
        @DisplayName("대소문자 구분 없이 이메일 중복 체크가 동작한다")
        void shouldCheckEmailCaseInsensitive() {
            // Given
            String duplicateEmail = "existing@email.com";
            User user = User.of(duplicateEmail, "password1234", "이재훈", "01077779999");
            when(userRepository.existsByEmail(duplicateEmail)).thenReturn(true);

            // When & Then
            ValidationException exception = assertThrows(ValidationException.class,
                () -> userService.register(user));
            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_EMAIL);
            verify(userRepository).existsByEmail(duplicateEmail);
        }
    }

    @Nested
    @DisplayName("비밀번호 검증")
    class PasswordValidation {
        @ParameterizedTest
        @ValueSource(strings = {"short", "123456789", "thispasswordiswaytoolongforvalidation"})
        @DisplayName("비밀번호가 10자 미만이거나 20자 초과이면 실패한다")
        void failIfPasswordLengthIsInvalid(String password) {
            // Given
            User user = User.of("test@email.com", password, "이재훈", "01012345678");

            // When & Then
            ValidationException exception = assertThrows(ValidationException.class, () -> userService.register(user));
            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PASSWORD_LENGTH_INVALID);
            verify(userRepository, never()).save(any(User.class));
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"", " "})
        @DisplayName("비밀번호가 null, 빈값, 공백이면 실패한다")
        void failIfPasswordIsNullOrBlank(String password) {
            User user = User.of("test@email.com", password, "이재훈", "01012345678");
            ValidationException exception = assertThrows(ValidationException.class, () -> userService.register(user));
            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PASSWORD_REQUIRED);
            verify(userRepository, never()).save(any(User.class));
        }

        @Test
        @DisplayName("비밀번호는 반드시 암호화되어 저장된다")
        void passwordShouldBeEncoded() {
            // Given
            String rawPassword = "password1234";
            String encodedPassword = "encoded_password_value";
            User user = User.of("test@email.com", rawPassword, "이재훈", "01077779999");

            when(userRepository.existsByEmail(user.getEmail())).thenReturn(false);
            when(passwordEncoder.encode(rawPassword)).thenReturn(encodedPassword);
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

            // When
            User savedUser = userService.register(user);

            // Then
            verify(passwordEncoder).encode(rawPassword);
            verify(userRepository).save(userCaptor.capture());
            assertThat(userCaptor.getValue().getPassword()).isEqualTo(encodedPassword);
            assertThat(savedUser.getPassword()).isEqualTo(encodedPassword);
        }
    }

    @Nested
    @DisplayName("이름 검증")
    class NameValidation {
        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"", " "})
        @DisplayName("이름이 null, 빈값, 공백이면 실패한다")
        void failIfNameIsNullOrBlank(String name) {
            // Given
            User user = User.of("test@email.com", "password1234", name, "01012345678");

            // When & Then
            ValidationException exception = assertThrows(ValidationException.class, () -> userService.register(user));
            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NAME_REQUIRED);
            verify(userRepository, never()).save(any(User.class));
        }

        @Test
        @DisplayName("이름이 20자를 초과하면 실패한다")
        void failIfNameIsTooLong() {
            String longName = "이름이이름이이름이이름이이름이이름이이름이"; // 21자
            User user = User.of("test@email.com", "password1234", longName, "01012345678");
            ValidationException exception = assertThrows(ValidationException.class, () -> userService.register(user));
            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NAME_TOO_LONG);
            verify(userRepository, never()).save(any(User.class));
        }
    }

    @Nested
    @DisplayName("전화번호 검증")
    class PhoneValidation {
        
        @ParameterizedTest
        @ValueSource(strings = {"010-1234-567", "123456789", "abcdefghijk", "010123456789"})
        @DisplayName("전화번호가 숫자가 아니거나 자리수가 틀리면 실패한다")
        void failIfPhoneFormatIsInvalid(String phone) {
            // Given
            User user = User.of("test@email.com", "password1234", "이재훈", phone);

            // When & Then
            ValidationException exception = assertThrows(ValidationException.class, () -> userService.register(user));
            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_PHONE_FORMAT);
            verify(userRepository, never()).save(any(User.class));
        }
    }
}
