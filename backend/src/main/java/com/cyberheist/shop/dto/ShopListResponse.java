package com.cyberheist.shop.dto;

import java.util.List;

public record ShopListResponse(List<ShopItemResponse> items, long coins) {
}
