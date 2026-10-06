package com.cyberheist.shop;

import com.cyberheist.common.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "items")
public class Item extends AuditableEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "code", nullable = false, length = 64, unique = true)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", nullable = false, length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 32)
    private ItemCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "rarity", nullable = false, length = 16)
    private ItemRarity rarity;

    @Enumerated(EnumType.STRING)
    @Column(name = "equipment_slot", nullable = false, length = 16)
    private EquipmentSlot equipmentSlot;

    @Column(name = "price", nullable = false)
    private long price;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "stackable", nullable = false)
    private boolean stackable;

    protected Item() {
    }

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public ItemCategory getCategory() { return category; }
    public ItemRarity getRarity() { return rarity; }
    public EquipmentSlot getEquipmentSlot() { return equipmentSlot; }
    public long getPrice() { return price; }
    public boolean isActive() { return active; }
    public boolean isStackable() { return stackable; }
}
