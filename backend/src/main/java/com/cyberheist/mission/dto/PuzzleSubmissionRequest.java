package com.cyberheist.mission.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * A player's answer to one puzzle.
 *
 * <p>Two fields, and that is the whole attack surface of the submission
 * endpoint: an id to look up and a string to compare. There is deliberately no
 * {@code success}, {@code score}, {@code xp} or {@code coins} field, because a
 * request DTO that can express them is a request DTO somebody will eventually
 * populate.
 *
 * @param answer what the player typed; capped at 200 characters so a puzzle can
 *               never be used as a place to write arbitrary data
 */
public record PuzzleSubmissionRequest(
        @NotNull(message = "A puzzle id is required")
        UUID puzzleId,

        @NotNull(message = "An answer is required")
        @Size(max = 200, message = "Answer must be at most 200 characters")
        String answer
) {
}