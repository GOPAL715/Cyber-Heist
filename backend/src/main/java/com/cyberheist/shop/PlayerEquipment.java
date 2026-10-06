package com.cyberheist.shop;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "player_equipment")
public class PlayerEquipment {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "slot", nullable = false, length = 16)
    private EquipmentSlot slot;

    @Column(name = "inventory_item_id", nullable = false)
    private UUID inventoryItemId;

    @Column(name = "equipped_at", nullable = false)
    private Instant equippedAt;

    protected PlayerEquipment() {
    }

    public PlayerEquipment(UUID id, UUID userId, EquipmentSlot slot, UUID inventoryItemId, Instant equippedAt) {
        this.id = id;
        this.userId = userId;
        this.slot = slot;
        this.inventoryItemId = inventoryItemId;
        this.equippedAt = equippedAt;
    }

    public void reequip(UUID inventoryItemId, Instant now) {
        this.inventoryItemId = inventoryItemId;
        this.equippedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public EquipmentSlot getSlot() { return slot; }
    public UUID getInventoryItemId() { return inventoryItemId; }
    public Instant getEquippedAt() { return equippedAt; }
}
