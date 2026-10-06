package com.cyberheist.shop;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "item_effects")
public class ItemEffect {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "item_id", nullable = false)
    private UUID itemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "effect_type", nullable = false, length = 32)
    private ItemEffectType effectType;

    @Column(name = "effect_value", nullable = false)
    private int effectValue;

    protected ItemEffect() {
    }

    public UUID getId() { return id; }
    public UUID getItemId() { return itemId; }
    public ItemEffectType getEffectType() { return effectType; }
    public int getEffectValue() { return effectValue; }
}
