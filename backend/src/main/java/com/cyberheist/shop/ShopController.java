package com.cyberheist.shop;

import com.cyberheist.common.ApiResponse;
import com.cyberheist.security.CurrentUser;
import com.cyberheist.shop.dto.PurchaseResponse;
import com.cyberheist.shop.dto.ShopItemResponse;
import com.cyberheist.shop.dto.ShopListResponse;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Shop endpoints.
 *
 * <p>Controllers here hold no logic and take no user id: the caller comes from
 * {@link CurrentUser}, which reads the security context. That is what makes
 * every route below act on the authenticated player only, including the purchase.
 *
 * <p>None of these methods accepts a request body on the purchase route. There is
 * no DTO to bind, so a body containing a price, a coin total, a rarity or a
 * bonus has no field it could occupy - the purchase is not "ignoring" those
 * values, it has no way to accept them.
 */
@RestController
@RequestMapping("/api/v1/player/shop")
public class ShopController {

    private final ShopService shopService;
    private final PurchaseService purchaseService;
    private final CurrentUser currentUser;

    public ShopController(ShopService shopService,
                          PurchaseService purchaseService,
                          CurrentUser currentUser) {
        this.shopService = shopService;
        this.purchaseService = purchaseService;
        this.currentUser = currentUser;
    }

    /** The active catalogue plus the caller's balance and owned flags. */
    @GetMapping
    public ApiResponse<ShopListResponse> shop() {
        return ApiResponse.of(shopService.catalogue(currentUser.requireId()));
    }

    /** One item's server-defined detail. */
    @GetMapping("/items/{itemId}")
    public ApiResponse<ShopItemResponse> item(@PathVariable UUID itemId) {
        return ApiResponse.of(shopService.detail(currentUser.requireId(), itemId));
    }

    /**
     * Buys an item at its catalogue price.
     *
     * <p>201 because a resource - the inventory row - was created.
     */
    @PostMapping("/items/{itemId}/purchase")
    public ResponseEntity<ApiResponse<PurchaseResponse>> purchase(@PathVariable UUID itemId) {
        PurchaseResponse purchased = purchaseService.purchase(currentUser.requireId(), itemId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(purchased, "Purchased " + purchased.code()));
    }
}