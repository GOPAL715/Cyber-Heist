package com.cyberheist.shop.dto;

import java.util.List;

public record InventoryListResponse(List<InventoryItemResponse> items) {
}
