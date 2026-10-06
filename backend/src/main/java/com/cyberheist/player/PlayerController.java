package com.cyberheist.player;

import com.cyberheist.common.ApiResponse;
import com.cyberheist.energy.EnergyService;
import com.cyberheist.energy.EnergySnapshot;
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
    private final EnergyService energyService;

    public PlayerController(PlayerProfileRepository profileRepository,
                            UserRepository userRepository,
                            CurrentUser currentUser,
                            ProgressionService progressionService,
                            EnergyService energyService) {
        this.profileRepository = profileRepository;
        this.userRepository = userRepository;
        this.currentUser = currentUser;
        this.progressionService = progressionService;
        this.energyService = energyService;
    }

    /**
     * Returns the caller's own profile, with energy brought up to date first.
     *
     * <p>Refreshing here is what makes the displayed balance trustworthy: it is
     * the same figure the start endpoint tests affordability against, rather than
     * a stale one the client has been counting down towards on its own.
     */
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

        EnergySnapshot energy = energyService.currentFor(userId)
                .orElseThrow(() -> ResourceNotFoundException.of("Player profile", userId));

        return ApiResponse.of(new PlayerProfileResponse(
                profile.getId(),
                username,
                profile.getDisplayName(),
                profile.getLevel(),
                profile.getExperience(),
                progression.xpIntoLevel(),
                progression.xpForNextLevel(),
                profile.getCoins(),
                energy.energy(),
                energy.maximum(),
                energy.regenerationEnabled(),
                energy.regenerationAmount(),
                energy.regenerationIntervalSeconds(),
                energy.nextRegenerationAt()
        ));
    }
}
