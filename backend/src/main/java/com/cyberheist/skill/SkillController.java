package com.cyberheist.skill;

import com.cyberheist.common.ApiResponse;
import com.cyberheist.security.CurrentUser;
import com.cyberheist.skill.dto.SkillTreeResponse;
import com.cyberheist.skill.dto.SkillUnlockResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Skill tree endpoints.
 *
 * <p>As in Phase 4, the controller holds no logic and accepts no user id: the
 * caller comes from {@link CurrentUser}, so every route acts on the
 * authenticated player only.
 *
 * <p>The unlock route declares <strong>no request body</strong>. "Take this
 * skill" is the whole message. There is no cost, level, effect or prerequisite
 * field for a client to populate, which is why this endpoint has no DTO at all -
 * the values all come from the tables.
 *
 * <p>There is deliberately no {@code PUT /player/skill-points}: skill points are
 * granted by level-ups and spent by unlocking, and neither direction is
 * something a client should be able to state.
 */
@RestController
@RequestMapping("/api/v1/player/skills")
public class SkillController {

    private final SkillTreeService skillTreeService;
    private final CurrentUser currentUser;

    public SkillController(SkillTreeService skillTreeService, CurrentUser currentUser) {
        this.skillTreeService = skillTreeService;
        this.currentUser = currentUser;
    }

    /**
     * The caller's skill tree: balance, every skill, levels, prerequisites and
     * the per-source bonus breakdown.
     */
    @GetMapping
    public ApiResponse<SkillTreeResponse> tree() {
        return ApiResponse.of(skillTreeService.tree(currentUser.requireId()));
    }

    /**
     * Takes the next level of one skill.
     *
     * <p>{@code 200} rather than {@code 201}: an existing skill is being
     * modified, and the very first unlock creates a row but does not create the
     * resource the URL names.
     */
    @PostMapping("/{skillId}/unlock")
    public ApiResponse<SkillUnlockResponse> unlock(@PathVariable UUID skillId) {
        return ApiResponse.of(
                skillTreeService.unlock(currentUser.requireId(), skillId),
                "Skill upgraded");
    }
}