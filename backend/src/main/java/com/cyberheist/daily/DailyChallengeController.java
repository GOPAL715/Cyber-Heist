package com.cyberheist.daily;

import com.cyberheist.common.ApiResponse;
import com.cyberheist.game.PlayerMilestoneService;
import com.cyberheist.player.PlayerProfile;
import com.cyberheist.player.PlayerProfileRepository;
import com.cyberheist.security.CurrentUser;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Today's objectives and the player's streak.
 *
 * <p>The only mutating route is {@code POST /daily/evaluate}, and it declares no
 * request body at all. That is the design: the client cannot say "I did three
 * missions" or "give me today's 100 XP" because there is nowhere to put either. The
 * server re-reads its own counters, works out what is genuinely due, and pays it.
 *
 * <p>Everything here is the authenticated caller's own state, resolved from the token.
 */
@RestController
@RequestMapping("/api/v1/player/daily")
public class DailyChallengeController {

    private final DailyChallengeService dailyChallenges;
    private final StreakService streaks;
    private final PlayerMilestoneService milestones;
    private final PlayerProfileRepository profiles;
    private final CurrentUser currentUser;

    public DailyChallengeController(DailyChallengeService dailyChallenges,
                                    StreakService streaks,
                                    PlayerMilestoneService milestones,
                                    PlayerProfileRepository profiles,
                                    CurrentUser currentUser) {
        this.dailyChallenges = dailyChallenges;
        this.streaks = streaks;
        this.milestones = milestones;
        this.profiles = profiles;
        this.currentUser = currentUser;
    }

    /**
     * Today's objectives with the caller's progress, plus their streak.
     *
     * <p>Includes the business date the server used, so the page can label the day
     * without consulting the browser's clock - which is the whole point of having a
     * configured business timezone.
     */
    @GetMapping
    public ApiResponse<DailyOverview> today() {
        UUID userId = currentUser.requireId();

        List<DailyChallengeView> challenges = dailyChallenges.todayFor(userId);
        StreakView streak = streaks.current(userId);

        return ApiResponse.of(new DailyOverview(dailyChallenges.businessDate(), challenges, streak));
    }

    /**
     * Recomputes progress from real player state and completes anything now due.
     *
     * <p>No body. Safe to call repeatedly: a completed objective is already marked,
     * so a second call reports nothing new and pays nothing.
     *
     * <p>Not strictly necessary - reading the daily page evaluates too - but it lets a
     * client that has just finished something confirm immediately without inventing a
     * way to claim anything.
     */
    @PostMapping("/evaluate")
    public ApiResponse<DailyEvaluation> evaluate() {
        UUID userId = currentUser.requireId();
        PlayerProfile profile = profiles.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("Profile missing for " + userId));

        milestones.evaluateOnly(userId, profile);

        return ApiResponse.of(new DailyEvaluation(
                dailyChallenges.businessDate(),
                dailyChallenges.todayFor(userId),
                streaks.current(userId)));
    }

    /**
     * The daily page payload.
     *
     * @param date the business date in the configured timezone, decided server-side
     */
    public record DailyOverview(LocalDate date, List<DailyChallengeView> challenges,
                                StreakView streak) {
    }

    /** The result of an explicit evaluation, for the completion notification. */
    public record DailyEvaluation(LocalDate date, List<DailyChallengeView> challenges,
                                  StreakView streak) {
    }
}
