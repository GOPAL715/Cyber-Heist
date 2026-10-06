package com.cyberheist.shop.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record EquipRequest(@NotNull UUID inventoryItemId) {
}
