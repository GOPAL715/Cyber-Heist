package com.cyberheist.mission;

import com.cyberheist.common.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A player's progress on one mission.
 *
 * <p>At most one row exists per (player, mission), enforced by the
 * {@code uq_mission_progress_user_mission} unique constraint as well as by the
 * service layer.
 */
@Entity
@Table(name = "mission_progress")
public class MissionProgress extends AuditableEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "mission_id", nullable = false)
    private UUID missionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private MissionStatus status;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    protected MissionProgress() {
        // for JPA
    }

    public MissionProgress(UUID id, UUID userId, UUID missionId) {
        this.id = id;
        this.userId = userId;
        this.missionId = missionId;
        this.status = MissionStatus.NOT_STARTED;
        this.attemptCount = 0;
    }

    /** Marks the mission as started, stamping the start time on the first attempt. */
    public void start(Instant now) {
        this.status = MissionStatus.IN_PROGRESS;
        this.startedAt = now;
        this.attemptCount++;
    }

    /** Marks the mission as completed. Only ever called once per progress row. */
    public void complete(Instant now) {
        this.status = MissionStatus.COMPLETED;
        if (this.startedAt == null) {
            // Defensive: the schema requires both stamps on completion.
            this.startedAt = now;
        }
        this.completedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getMissionId() {
        return missionId;
    }

    public MissionStatus getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public int getAttemptCount() {
        return attemptCount;
    }
}