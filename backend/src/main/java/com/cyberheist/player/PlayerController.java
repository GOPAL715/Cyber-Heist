package com.cyberheist.player;

import com.cyberheist.common.ApiResponse;
import com.cyberheist.exception.ResourceNotFoundException;
import com.cyberheist.player.dto.PlayerProfileResponse;
import com.cyberheist.security.CurrentUser;
import com.cyberheist.user.User;
import com.cyberheist.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Player profile endpoints.
 *
 * <p>There is deliberately no endpoint taking a user id: the caller is always
 * resolved from the security context, so ownership is enforced by the backend
 * rather than by anything the client sends.
 */
@RestController
@RequestMapping("/api/v1/player")
public class PlayerController {

    private final PlayerProfileRepository profileRepository;
    private final UserRepository userRepository;
    private final CurrentUser currentUser;

    public PlayerController(PlayerProfileRepository profileRepository,
                            UserRepository userRepository,
                            CurrentUser currentUser) {
        this.profileRepository = profileRepository;
        this.userRepository = userRepository;
        this.currentUser = currentUser;
    }

    /** Returns the caller's own profile. */
    @GetMapping("/profile")
    public ApiResponse<PlayerProfileResponse> myProfile() {
        UUID userId = currentUser.requireId();

        PlayerProfile profile = profileRepository.findByUserId(userId)
                .orElseThrow(() -> ResourceNotFoundException.of("Player profile", userId));

        String username = userRepository.findById(userId)
                .map(User::getUsername)
                .orElse(profile.getDisplayName());

        return ApiResponse.of(new PlayerProfileResponse(
                profile.getId(),
                username,
                profile.getDisplayName(),
                profile.getLevel(),
                profile.getExperience(),
                profile.getCoins(),
                profile.getEnergy()
        ));
    }
}