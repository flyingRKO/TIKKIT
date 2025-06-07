package com.tikkit.api.user.service;

import com.tikkit.api.global.exception.ErrorCode;
import com.tikkit.api.global.exception.ValidationException;
import com.tikkit.api.user.entity.User;
import com.tikkit.api.user.validation.UserValidationConstants;

import java.util.regex.Pattern;

public class UserValidator {
    private static final Pattern EMAIL_PATTERN = Pattern.compile(UserValidationConstants.EMAIL_REGEX);
    private static final Pattern PHONE_PATTERN = Pattern.compile(UserValidationConstants.PHONE_REGEX);

    public static void validate(User user) {
        validateEmail(user.getEmail());
        validatePassword(user.getPassword());
        validateName(user.getName());
        validatePhone(user.getPhone());
    }

    private static void validateEmail(String email) {
        if (email == null || email.trim().isEmpty()) {
            throw new ValidationException(ErrorCode.EMAIL_REQUIRED, ErrorCode.EMAIL_REQUIRED.getMessage());
        }
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw new ValidationException(ErrorCode.INVALID_EMAIL_FORMAT, ErrorCode.INVALID_EMAIL_FORMAT.getMessage());
        }
    }

    private static void validatePassword(String password) {
        if (password == null || password.trim().isEmpty()) {
            throw new ValidationException(ErrorCode.PASSWORD_REQUIRED, ErrorCode.PASSWORD_REQUIRED.getMessage());
        }
        if (password.length() < UserValidationConstants.PASSWORD_MIN_LENGTH
                || password.length() > UserValidationConstants.PASSWORD_MAX_LENGTH) {
            throw new ValidationException(ErrorCode.PASSWORD_LENGTH_INVALID, ErrorCode.PASSWORD_LENGTH_INVALID.getMessage());
        }
    }

    private static void validateName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new ValidationException(ErrorCode.NAME_REQUIRED, ErrorCode.NAME_REQUIRED.getMessage());
        }
        if (name.length() > 20) {
            throw new ValidationException(ErrorCode.NAME_TOO_LONG, ErrorCode.NAME_TOO_LONG.getMessage());
        }
    }

    private static void validatePhone(String phone) {
        if (phone == null || phone.trim().isEmpty()) {
            throw new ValidationException(ErrorCode.PHONE_REQUIRED, ErrorCode.PHONE_REQUIRED.getMessage());
        }
        if (!PHONE_PATTERN.matcher(phone).matches()) {
            throw new ValidationException(ErrorCode.INVALID_PHONE_FORMAT, ErrorCode.INVALID_PHONE_FORMAT.getMessage());
        }
    }
} 