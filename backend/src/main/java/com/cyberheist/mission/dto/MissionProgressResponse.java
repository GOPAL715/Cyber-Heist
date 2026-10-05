package com.cyberheist.mission.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * The caller's progress on a single mission.
 *
 * <p>Always belongs to the authenticated player: the controller resolves the
 * user from the security context, so there is no way to request someone else's
 * progress.
 */
public record MissionProgressResponse(
        UUID missionId,
        String status,
        int attemptCount,
        Instant startedAt,
        Instant completedAt
) {
}