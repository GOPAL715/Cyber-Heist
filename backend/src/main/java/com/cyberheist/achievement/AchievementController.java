package com.cyberheist.achievement;

import com.cyberheist.common.ApiResponse;
import com.cyberheist.exception.AchievementNotFoundException;
import com.cyberheist.game.GameProperties;
import com.cyberheist.game.PlayerMilestoneService;
import com.cyberheist.player.PlayerProfile;
import com.cyberheist.player.PlayerProfileRepository;
import com.cyberheist.security.CurrentUser;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only views of the milestone catalogue.
 *
 * <p>There is no endpoint here that unlocks anything, sets progress or pays a
 * reward. Each handler first asks {@link PlayerMilestoneService} to evaluate, which
 * credits anything genuinely due, and then reports the result - so a player who
 * earned a milestone offline from the event that triggered it sees it here rather
 * than having to perform another action to trigger a catch-up.
 *
 * <p>Every route resolves the caller from the access token. None of them accepts a
 * user id, an achievement id, a progress figure or a reward, so there is no request
 * shape anywhere on this controller that could state what a player has done or what
 * they are owed.
 */
@RestController
@RequestMapping("/api/v1/player/achievements")
public class AchievementController {

    private final AchievementService achievements;
    private final PlayerMilestoneService milestones;
    private final PlayerProfileRepository profiles;
    private final CurrentUser currentUser;
    private final GameProperties properties;

    public AchievementController(AchievementService achievements,
                                 PlayerMilestoneService milestones,
                                 PlayerProfileRepository profiles,
                                 CurrentUser currentUser,
                                 GameProperties properties) {
        this.achievements = achievements;
        this.milestones = milestones;
        this.profiles = profiles;
        this.currentUser = currentUser;
        this.properties = properties;
    }

    /** The whole catalogue with the caller's state against every entry. */
    @GetMapping
    public ApiResponse<List<AchievementView>> list() {
        UUID userId = currentUser.requireId();
        PlayerProfile profile = requireProfile(userId);

        // Catch-up first, so the response reflects everything now due rather than
        // whatever was true when the last event happened.
        milestones.evaluateOnly(userId, profile);

        return ApiResponse.of(achievements.catalogueFor(userId, profile));
    }

    /** One achievement, addressed by its stable code. */
    @GetMapping("/{code}")
    public ApiResponse<AchievementView> detail(@PathVariable String code) {
        UUID userId = currentUser.requireId();
        PlayerProfile profile = requireProfile(userId);

        milestones.evaluateOnly(userId, profile);

        return ApiResponse.of(achievements.findByCode(userId, profile, code)
                .orElseThrow(AchievementNotFoundException::new));
    }

    /**
     * The most recent unlocks, newest first.
     *
     * <p>Declared before {@code /{code}} would matter only if the two could be
     * confused, and they cannot: Spring prefers the literal path. The limit comes from
     * configuration, so the query is always bounded and the client cannot ask for a
     * thousand rows.
     */
    @GetMapping("/recent")
    public ApiResponse<List<AchievementView>> recent() {
        UUID userId = currentUser.requireId();
        PlayerProfile profile = requireProfile(userId);

        milestones.evaluateOnly(userId, profile);

        return ApiResponse.of(achievements.recentFor(userId, profile,
                properties.recentAchievementLimit()));
    }

    private PlayerProfile requireProfile(UUID userId) {
        return profiles.findByUserId(userId)
                .orElseThrow(() -> new AchievementNotFoundException());
    }
}
