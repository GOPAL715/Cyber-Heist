package com.cyberheist.user;

import com.cyberheist.auth.UserMapper;
import com.cyberheist.auth.dto.UserResponse;
import com.cyberheist.common.ApiResponse;
import com.cyberheist.exception.ResourceNotFoundException;
import com.cyberheist.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Account information for the caller. Never exposes another user's data.
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserRepository userRepository;
    private final CurrentUser currentUser;

    public UserController(UserRepository userRepository, CurrentUser currentUser) {
        this.userRepository = userRepository;
        this.currentUser = currentUser;
    }

    /** Returns the authenticated account. */
    @GetMapping("/me")
    public ApiResponse<UserResponse> me() {
        UUID userId = currentUser.requireId();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ResourceNotFoundException.of("User", userId));
        return ApiResponse.of(UserMapper.toResponse(user));
    }
}