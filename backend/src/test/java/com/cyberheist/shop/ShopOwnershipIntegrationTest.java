package com.cyberheist.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cyberheist.IntegrationTestSupport;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Cross-player isolation.
 *
 * <p>Every rule here is enforced by the same mechanism: each service query is
 * keyed on the user id the controller read from the security context, so another
 * player's rows are simply not in the result set. These tests exist to prove no
 * endpoint accidentally widens a query, and that no endpoint accepts a user id
 * from the request.
 */
class ShopOwnershipIntegrationTest extends IntegrationTestSupport {

    @Test
    @DisplayName("a player sees only their own inventory")
    void inventoryIsScopedToCaller() throws Exception {
        String tokenA = signInNewPlayer("own_a", "own_a@example.com");
        registerPlayer("own_b", "own_b@example.com");

        setCoins("own_a@example.com", 2000);
        purchase(tokenA, itemId("NEURAL_PROCESSOR"));

        JsonNode inventoryA = getData(tokenA, "/api/v1/player/inventory").path("items");
        JsonNode inventoryB = getData(
                loginAndGetAccessToken("own_b@example.com", VALID_PASSWORD),
                "/api/v1/player/inventory").path("items");

        assertThat(codes(inventoryA)).contains("NEURAL_PROCESSOR");
        assertThat(codes(inventoryB)).doesNotContain("NEURAL_PROCESSOR");
        // B's inventory holds only their own starter item.
        assertThat(codes(inventoryB)).containsExactly("BASIC_LAPTOP");
    }

    @Test
    @DisplayName("a player cannot equip another player's inventory row")
    void cannotEquipAnotherPlayersItem() throws Exception {
        String tokenA = signInNewPlayer("eq_a", "eq_a@example.com");
        registerPlayer("own_b2", "own_b2@example.com");
        String tokenB = loginAndGetAccessToken("own_b2@example.com", VALID_PASSWORD);

        setCoins("eq_a@example.com", 2000);
        purchase(tokenA, itemId("ADAPTIVE_FIREWALL"));

        UUID aInventoryRow = ownedItem("eq_a@example.com", "ADAPTIVE_FIREWALL").orElseThrow().getId();

        mockMvc.perform(authPost("/api/v1/player/equipment/SECURITY", tokenB)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new EquipPayload(aInventoryRow))))
                .andExpect(status().isNotFound());

        // And A's loadout was not touched by the attempt.
        assertThat(slotItemCode(tokenA, EquipmentSlot.SECURITY)).isNull();
    }

    @Test
    @DisplayName("a guessed inventory id belonging to nobody is not found")
    void cannotEquipUnknownInventoryRow() throws Exception {
        String token = signInNewPlayer("eq_ghost", "eq_ghost@example.com");

        mockMvc.perform(authPost("/api/v1/player/equipment/MAIN_DEVICE", token)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new EquipPayload(randomUuid()))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("unequipping is scoped to the caller's own loadout")
    void cannotUnequipAnotherPlayersItem() throws Exception {
        String tokenA = signInNewPlayer("un_a", "un_a@example.com");
        registerPlayer("own_un_b", "own_un_b@example.com");
        String tokenB = loginAndGetAccessToken("own_un_b@example.com", VALID_PASSWORD);

        // A equips something into PROCESSOR; B empties their own PROCESSOR.
        setCoins("un_a@example.com", 2000);
        purchase(tokenA, itemId("BASIC_PROCESSOR"));
        UUID row = ownedItem("un_a@example.com", "BASIC_PROCESSOR").orElseThrow().getId();
        equip(tokenA, EquipmentSlot.PROCESSOR, row);
        assertThat(slotItemCode(tokenA, EquipmentSlot.PROCESSOR)).isEqualTo("BASIC_PROCESSOR");

        mockMvc.perform(authDelete("/api/v1/player/equipment/PROCESSOR", tokenB))
                .andExpect(status().isOk());

        // B's action touched only B's slot; A is still equipped.
        assertThat(slotItemCode(tokenA, EquipmentSlot.PROCESSOR)).isEqualTo("BASIC_PROCESSOR");
        assertThat(slotItemCode(tokenB, EquipmentSlot.PROCESSOR)).isNull();
    }

    @Test
    @DisplayName("a player's coins cannot be spent by another player")
    void cannotSpendAnotherPlayersCoins() throws Exception {
        String tokenA = signInNewPlayer("spend_a", "spend_a@example.com");
        registerPlayer("own_spend_b", "own_spend_b@example.com");
        String tokenB = loginAndGetAccessToken("own_spend_b@example.com", VALID_PASSWORD);

        // A is rich, B is not. B must not be able to spend A's money.
        setCoins("spend_a@example.com", 100000);
        setCoins("own_spend_b@example.com", 0);

        assertThat(purchaseExpectingFailure(tokenB, itemId("QUANTUM_PROCESSOR"))
                .getResponse().getStatus()).isEqualTo(400);

        assertThat(profileOf("spend_a@example.com").getCoins()).isEqualTo(100000);
        assertThat(ownedItem("own_spend_b@example.com", "QUANTUM_PROCESSOR")).isEmpty();
    }

    @Test
    @DisplayName("a request carrying someone else's user id is ignored")
    void ignoresClientSuppliedUserId() throws Exception {
        String token = signInNewPlayer("uid_player", "uid_player@example.com");
        registerPlayer("own_victim", "own_victim@example.com");
        UUID victimId = userIdOf("own_victim@example.com");
        setCoins("own_victim@example.com", 5000);

        mockMvc.perform(authGet(
                        "/api/v1/player/inventory?userId=" + victimId, token))
                .andExpect(status().isOk());

        JsonNode mine = getData(token, "/api/v1/player/inventory").path("items");
        assertThat(codes(mine)).containsExactly("BASIC_LAPTOP");
        assertThat(ownedItem("own_victim@example.com", "BASIC_LAPTOP")).isPresent();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authDelete(
            String url, String token) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete(url).header("Authorization", "Bearer " + token);
    }

    private java.util.List<String> codes(JsonNode items) {
        java.util.List<String> out = new java.util.ArrayList<>();
        items.forEach(item -> out.add(item.path("code").asText()));
        return out;
    }

    /** The code equipped in a slot, or null when the slot is empty. */
    private String slotItemCode(String token, EquipmentSlot slot) throws Exception {
        for (JsonNode entry : getData(token, "/api/v1/player/equipment").path("equipment")) {
            if (slot.name().equals(entry.path("slot").asText())) {
                JsonNode item = entry.path("item");
                return item.isMissingNode() || item.isNull() ? null : item.path("code").asText();
            }
        }
        throw new AssertionError("Slot " + slot + " was not present in the loadout response");
    }
}