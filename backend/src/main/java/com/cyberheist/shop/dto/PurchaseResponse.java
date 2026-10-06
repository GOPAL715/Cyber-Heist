package com.cyberheist.shop.dto;

import java.util.UUID;

public record PurchaseResponse(
        UUID inventoryId,
        UUID itemId,
        String code,
        String name,
        long pricePaid,
        long coins
) {
}
