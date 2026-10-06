package com.cyberheist.shop;

import com.cyberheist.common.ApiResponse;
import com.cyberheist.security.CurrentUser;
import com.cyberheist.shop.dto.EquipmentListResponse;
import com.cyberheist.shop.dto.InventoryItemResponse;
import com.cyberheist.shop.dto.InventoryListResponse;
import com.cyberheist.shop.dto.EquipRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inventory and equipment endpoints.
 *
 * <p>Both resources are strictly per-caller. There is no path variable, query
 * parameter or body field that identifies whose inventory is meant: the only id
 * a client ever sends is the inventory row it wants to act on, and ownership of
 * that row is verified server-side before anything changes.
 *
 * <p>The slot arrives as a path variable and is parsed into
 * {@link EquipmentSlot}. An unrecognised slot never reaches a service, so it is
 * rejected as a bad value rather than being silently coerced to a default.
 */
@RestController
@RequestMapping("/api/v1/player")
public class InventoryController {

    private final InventoryService inventoryService;
    private final EquipmentService equipmentService;
    private final LoadoutService loadoutService;
    private final CurrentUser currentUser;

    public InventoryController(InventoryService inventoryService,
                               EquipmentService equipmentService,
                               LoadoutService loadoutService,
                               CurrentUser currentUser) {
        this.inventoryService = inventoryService;
        this.equipmentService = equipmentService;
        this.loadoutService = loadoutService;
        this.currentUser = currentUser;
    }

    /** Everything the caller owns, with rarity, effects and equipped state. */
    @GetMapping("/inventory")
    public ApiResponse<InventoryListResponse> inventory() {
        return ApiResponse.of(inventoryService.inventory(currentUser.requireId()));
    }

    /** The loadout: every slot, filled or empty, plus the aggregated bonuses. */
    @GetMapping("/equipment")
    public ApiResponse<EquipmentListResponse> equipment() {
        return ApiResponse.of(loadoutService.loadout(currentUser.requireId()));
    }

    /**
     * Equips an owned item into a slot, replacing whatever was there.
     *
     * <p>The body carries exactly one field: which inventory row to equip.
     */
    @PostMapping("/equipment/{slot}")
    public ApiResponse<InventoryItemResponse> equip(@PathVariable EquipmentSlot slot,
                                                    @Valid @RequestBody EquipRequest request) {
        return ApiResponse.of(
                equipmentService.equip(currentUser.requireId(), slot, request.inventoryItemId()),
                "Equipped " + slot);
    }

    /** Empties a slot. The item stays in the inventory. */
    @DeleteMapping("/equipment/{slot}")
    public ApiResponse<Void> unequip(@PathVariable EquipmentSlot slot) {
        equipmentService.unequip(currentUser.requireId(), slot);
        return ApiResponse.message("Unequipped " + slot);
    }
}