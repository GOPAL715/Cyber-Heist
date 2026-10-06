package com.cyberheist.boss;

import com.cyberheist.boss.dto.BossDetail;
import com.cyberheist.boss.dto.BossListItem;
import com.cyberheist.boss.dto.BossStageSubmission;
import com.cyberheist.boss.dto.EncounterState;
import com.cyberheist.common.ApiResponse;
import com.cyberheist.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Boss endpoints.
 *
 * <p>As everywhere else in this API, the controller holds no logic and accepts no
 * user id: the caller comes from {@link CurrentUser}. That is what makes every
 * route below act on the authenticated player only, and it is why none of them
 * can be pointed at somebody else's encounter.
 *
 * <p>The start route declares <strong>no request body</strong>. The entry cost,
 * the opening phase, the boss integrity and the difficulty are all decided from
 * the catalogue. There is no DTO for them to bind to.
 *
 * <p>The submission body is {@link BossStageSubmission}: a puzzle id and an
 * answer. There is no damage, integrity, stage, reward, XP, coin or energy field,
 * which is the whole point — a request DTO that can express them is a request DTO
 * somebody eventually will.
 */
@RestController
@RequestMapping("/api/v1/player")
public class BossController {

    private final BossCatalogueService catalogueService;
    private final BossEncounterService encounterService;
    private final CurrentUser currentUser;

    public BossController(BossCatalogueService catalogueService,
                          BossEncounterService encounterService,
                          CurrentUser currentUser) {
        this.catalogueService = catalogueService;
        this.encounterService = encounterService;
        this.currentUser = currentUser;
    }

    /**
     * Every active boss with the caller's state for each.
     *
     * <p>Declared before {@code /bosses/{bossId}} so the literal segment wins for
     * the history path.
     */
    @GetMapping("/bosses")
    public ApiResponse<List<BossListItem>> bosses() {
        return ApiResponse.of(catalogueService.catalogue(currentUser.requireId()));
    }

    /** The caller's recent encounters, newest first and bounded. */
    @GetMapping("/bosses/history")
    public ApiResponse<List<EncounterState>> history() {
        return ApiResponse.of(encounterService.history(currentUser.requireId()));
    }

    /** One boss with its phases and the caller's history of it. */
    @GetMapping("/bosses/{bossId}")
    public ApiResponse<BossDetail> boss(@PathVariable UUID bossId) {
        return ApiResponse.of(catalogueService.detail(currentUser.requireId(), bossId));
    }

    /** Charges the entry cost and opens an encounter. */
    @PostMapping("/bosses/{bossId}/start")
    public ApiResponse<EncounterState> start(@PathVariable UUID bossId) {
        return ApiResponse.of(
                encounterService.start(currentUser.requireId(), bossId),
                "Encounter started");
    }

    /** The caller's live encounter, or 404 when there is none. */
    @GetMapping("/boss/encounter")
    public ApiResponse<EncounterState> currentEncounter() {
        return ApiResponse.of(encounterService.current(currentUser.requireId()));
    }

    /** Answers the live phase's puzzle. */
    @PostMapping("/boss/encounter/stage/submit")
    public ApiResponse<EncounterState> submitStage(@Valid @RequestBody BossStageSubmission submission) {
        return ApiResponse.of(
                encounterService.submitStage(currentUser.requireId(), submission),
                "Stage submitted");
    }
}