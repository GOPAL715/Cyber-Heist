package com.cyberheist.shop;

import com.cyberheist.common.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "player_inventory")
public class PlayerInventoryItem extends AuditableEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "item_id", nullable = false)
    private UUID itemId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    protected PlayerInventoryItem() {
    }

    public PlayerInventoryItem(UUID id, UUID userId, UUID itemId) {
        this.id = id;
        this.userId = userId;
        this.itemId = itemId;
        this.quantity = 1;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getItemId() { return itemId; }
    public int getQuantity() { return quantity; }
}
