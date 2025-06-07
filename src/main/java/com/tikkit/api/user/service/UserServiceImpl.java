package com.tikkit.api.user.service;

import com.tikkit.api.global.exception.ErrorCode;
import com.tikkit.api.global.exception.ValidationException;
import com.tikkit.api.user.entity.User;
import com.tikkit.api.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserServiceImpl implements UserService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public User register(User user) {
        
        UserValidator.validate(user);

        
        if (userRepository.existsByEmail(user.getEmail())) {
            throw new ValidationException(ErrorCode.DUPLICATE_EMAIL, ErrorCode.DUPLICATE_EMAIL.getMessage());
        }

        
        User userToSave = User.of(
                user.getEmail(),
                passwordEncoder.encode(user.getPassword()),
                user.getName(),
                user.getPhone()
        );

        
        return userRepository.save(userToSave);
    }

    @Override
    public boolean isEmailDuplicated(String email) {
        return userRepository.existsByEmail(email);
    }
} 