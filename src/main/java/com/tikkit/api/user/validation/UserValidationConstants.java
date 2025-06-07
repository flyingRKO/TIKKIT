package com.tikkit.api.user.validation;

public class UserValidationConstants {
    public static final String EMAIL_REGEX = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$";
    public static final String PHONE_REGEX = "^\\d{10,11}$";
    public static final int PASSWORD_MIN_LENGTH = 10;
    public static final int PASSWORD_MAX_LENGTH = 20;
    private UserValidationConstants() {}
} 