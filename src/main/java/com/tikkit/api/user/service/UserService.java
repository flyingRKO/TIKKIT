package com.tikkit.api.user.service;

import com.tikkit.api.user.entity.User;

public interface UserService {
    User register(User user);
    boolean isEmailDuplicated(String email);
}
