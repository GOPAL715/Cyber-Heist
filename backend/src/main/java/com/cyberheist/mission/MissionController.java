package com.cyberheist.mission;

import com.cyberheist.common.ApiResponse;
import com.cyberheist.mission.dto.MissionCompletionResponse;
import com.cyberheist.mission.dto.MissionProgressResponse;
import com.cyberheist.mission.dto.MissionResponse;
import com.cyberheist.mission.dto.MissionStartResponse;
import com.cyberheist.mission.dto.PuzzleSubmissionRequest;
import com.cyberheist.mission.dto.PuzzleSubmissionResponse;
import com.cyberheist.puzzle.dto.PuzzleChallengeView;
import com.cyberheist.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
 * <p>Start takes no request body and now also returns a puzzle. Complete takes
 * no body and can no longer pay anything on its own; {@code /puzzle/submit} is
 * the only route to rewards, and the only body it accepts anywhere in the
 * application is {@code {puzzleId, answer}}.
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

    /**
     * Starts a mission, spends its energy and returns the generated puzzle.
     *
     * <p>The body repeats every {@link MissionResponse} field so a Phase 2
     * client keeps working unchanged, and adds {@code puzzle} and {@code player}.
     * The puzzle carries no answer.
     */
    @PostMapping("/{missionId}/start")
    public ApiResponse<MissionStartResponse> startMission(@PathVariable UUID missionId) {
        UUID userId = currentUser.requireId();
        return ApiResponse.of(missionService.startMission(userId, missionId), "Mission started");
    }

    /**
     * The caller's current puzzle for a mission, re-derived from its seed.
     *
     * <p>Lets the client rebuild the puzzle screen after a reload without the
     * server resending the challenge at start time.
     */
    @GetMapping("/{missionId}/puzzle")
    public ApiResponse<PuzzleChallengeView> activePuzzle(@PathVariable UUID missionId) {
        UUID userId = currentUser.requireId();
        return ApiResponse.of(missionService.activePuzzle(userId, missionId));
    }

    /**
     * Submits an answer to the mission's puzzle.
     *
     * <p>The only body accepted anywhere in the game loop is
     * {@code {puzzleId, answer}}. The server decides the outcome, and awards
     * rewards only for a correct, unexpired answer to the caller's own puzzle on
     * this mission.
     */
    @PostMapping("/{missionId}/puzzle/submit")
    public ApiResponse<PuzzleSubmissionResponse> submitPuzzle(@PathVariable UUID missionId,
                                                              @Valid @RequestBody PuzzleSubmissionRequest request) {
        UUID userId = currentUser.requireId();
        PuzzleSubmissionResponse result = missionService.submitPuzzle(
                userId, missionId, request.puzzleId(), request.answer());
        return ApiResponse.of(result, result.outcome().headline());
    }

    /**
     * Reports a completed mission's reward record.
     *
     * <p>Kept for Phase 2 clients. It can no longer complete anything or pay
     * anything: only a solved puzzle writes {@code COMPLETED}.
     */
    @PostMapping("/{missionId}/complete")
    public ApiResponse<MissionCompletionResponse> completeMission(@PathVariable UUID missionId) {
        UUID userId = currentUser.requireId();
        return ApiResponse.of(missionService.completeMission(userId, missionId), "Mission complete");
    }
}