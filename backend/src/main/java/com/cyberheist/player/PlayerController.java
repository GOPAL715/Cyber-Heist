package com.cyberheist.player;

import com.cyberheist.common.ApiResponse;
import com.cyberheist.exception.ResourceNotFoundException;
import com.cyberheist.player.dto.PlayerProfileResponse;
import com.cyberheist.progression.ProgressionResult;
import com.cyberheist.progression.ProgressionService;
import com.cyberheist.security.CurrentUser;
import com.cyberheist.user.User;
import com.cyberheist.user.UserRepository;
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
    private final ProgressionService progressionService;

    public PlayerController(PlayerProfileRepository profileRepository,
                            UserRepository userRepository,
                            CurrentUser currentUser,
                            ProgressionService progressionService) {
        this.profileRepository = profileRepository;
        this.userRepository = userRepository;
        this.currentUser = currentUser;
        this.progressionService = progressionService;
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

        // The level band is resolved server-side so the client never has to
        // duplicate the curve just to draw a progress bar.
        ProgressionResult progression = progressionService.describe(profile);

        return ApiResponse.of(new PlayerProfileResponse(
                profile.getId(),
                username,
                profile.getDisplayName(),
                profile.getLevel(),
                profile.getExperience(),
                progression.xpIntoLevel(),
                progression.xpForNextLevel(),
                profile.getCoins(),
                profile.getEnergy()
        ));
    }
}