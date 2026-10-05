package com.cyberheist.mission;

import com.cyberheist.common.ApiResponse;
import com.cyberheist.mission.dto.MissionCompletionResponse;
import com.cyberheist.mission.dto.MissionProgressResponse;
import com.cyberheist.mission.dto.MissionResponse;
import com.cyberheist.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Mission endpoints for the authenticated player.
 *
 * <p>Every path is under {@code /api/v1/player} and the caller is always
 * resolved from the security context, so there is no endpoint that accepts a
 * user id. All of these require a valid access token: the security
 * configuration denies everything not explicitly public.
 *
 * <p>Start and complete take no request body. That is deliberate: the client
 * asks for an action and the server decides eligibility and rewards.
 */
@RestController
@RequestMapping("/api/v1/player/missions")
public class MissionController {

    private final MissionService missionService;
    private final CurrentUser currentUser;

    public MissionController(MissionService missionService, CurrentUser currentUser) {
        this.missionService = missionService;
        this.currentUser = currentUser;
    }

    /**
     * Lists missions for the caller, optionally narrowed to one category.
     *
     * @param category optional filter; an unknown value is rejected as 400 by
     *                 Spring's enum binding
     */
    @GetMapping
    public ApiResponse<List<MissionResponse>> listMissions(
            @RequestParam(required = false) MissionCategory category) {
        UUID userId = currentUser.requireId();
        return ApiResponse.of(missionService.listMissions(userId, category));
    }

    /** Returns a single mission with the caller's own status. */
    @GetMapping("/{missionId}")
    public ApiResponse<MissionResponse> getMission(@PathVariable UUID missionId) {
        UUID userId = currentUser.requireId();
        return ApiResponse.of(missionService.getMission(userId, missionId));
    }

    /** The caller's progress on a single mission. */
    @GetMapping("/{missionId}/progress")
    public ApiResponse<MissionProgressResponse> getProgress(@PathVariable UUID missionId) {
        UUID userId = currentUser.requireId();
        return ApiResponse.of(missionService.getProgress(userId, missionId));
    }

    /** Starts a mission, spending its energy cost. */
    @PostMapping("/{missionId}/start")
    public ApiResponse<MissionResponse> startMission(@PathVariable UUID missionId) {
        UUID userId = currentUser.requireId();
        return ApiResponse.of(missionService.startMission(userId, missionId), "Mission started");
    }

    /**
     * Completes a mission and returns the reward.
     *
     * <p>Safe to retry: a repeat call awards nothing and reports
     * {@code alreadyCompleted: true}.
     */
    @PostMapping("/{missionId}/complete")
    public ApiResponse<MissionCompletionResponse> completeMission(@PathVariable UUID missionId) {
        UUID userId = currentUser.requireId();
        return ApiResponse.of(missionService.completeMission(userId, missionId), "Mission complete");
    }
}