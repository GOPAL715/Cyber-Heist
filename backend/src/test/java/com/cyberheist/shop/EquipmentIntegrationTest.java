package com.cyberheist.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cyberheist.IntegrationTestSupport;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Equipping, swapping and unequipping.
 */
class EquipmentIntegrationTest extends IntegrationTestSupport {

    @Test
    @DisplayName("equips an owned item into its matching slot")
    void equipsItem() throws Exception {
        String token = signInNewPlayer("eq_ok", "eq_ok@example.com");
        setCoins("eq_ok@example.com", 2000);
        purchase(token, itemId("BASIC_PROCESSOR"));
        UUID row = ownedItem("eq_ok@example.com", "BASIC_PROCESSOR").orElseThrow().getId();

        JsonNode equipped = equip(token, EquipmentSlot.PROCESSOR, row);

        assertThat(equipped.path("code").asText()).isEqualTo("BASIC_PROCESSOR");
        assertThat(equipped.path("equipped").asBoolean()).isTrue();
        assertThat(equipped.path("equippedIn").asText()).isEqualTo("PROCESSOR");
    }

    @Test
    @DisplayName("marks the item equipped in the inventory view")
    void inventoryReportsEquippedState() throws Exception {
        String token = signInNewPlayer("eq_inv", "eq_inv@example.com");
        setCoins("eq_inv@example.com", 2000);
        purchase(token, itemId("INTRUSION_SUITE"));
        UUID row = ownedItem("eq_inv@example.com", "INTRUSION_SUITE").orElseThrow().getId();

        equip(token, EquipmentSlot.SOFTWARE, row);

        JsonNode software = findByCode(
                getData(token, "/api/v1/player/inventory").path("items"), "INTRUSION_SUITE");
        assertThat(software.path("equipped").asBoolean()).isTrue();

        // The starter laptop is untouched and still equipped.
        assertThat(findByCode(getData(token, "/api/v1/player/inventory").path("items"),
                "BASIC_LAPTOP").path("equipped").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("replaces the item already in a slot")
    void replacesItemInSameSlot() throws Exception {
        String token = signInNewPlayer("eq_swap", "eq_swap@example.com");
        setCoins("eq_swap@example.com", 5000);
        purchase(token, itemId("BASIC_PROCESSOR"));
        purchase(token, itemId("ENCRYPTED_PROCESSOR"));

        UUID cheap = ownedItem("eq_swap@example.com", "BASIC_PROCESSOR").orElseThrow().getId();
        UUID good = ownedItem("eq_swap@example.com", "ENCRYPTED_PROCESSOR").orElseThrow().getId();

        equip(token, EquipmentSlot.PROCESSOR, cheap);
        equip(token, EquipmentSlot.PROCESSOR, good);

        assertThat(slotItemCode(token, EquipmentSlot.PROCESSOR)).isEqualTo("ENCRYPTED_PROCESSOR");

        // The replaced item is still owned, just no longer equipped.
        JsonNode cheapItem = findByCode(
                getData(token, "/api/v1/player/inventory").path("items"), "BASIC_PROCESSOR");
        assertThat(cheapItem.path("equipped").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("refuses to put an item in the wrong slot")
    void refusesIncompatibleSlot() throws Exception {
        String token = signInNewPlayer("eq_wrong", "eq_wrong@example.com");
        setCoins("eq_wrong@example.com", 5000);
        purchase(token, itemId("BASIC_PROCESSOR"));
        UUID row = ownedItem("eq_wrong@example.com", "BASIC_PROCESSOR").orElseThrow().getId();

        mockMvc.perform(post("/api/v1/player/equipment/SECURITY", token)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new EquipPayload(row))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("PROCESSOR")));

        assertThat(slotItemCode(token, EquipmentSlot.SECURITY)).isNull();
    }

    @Test
    @DisplayName("rejects a slot that is not one of the five")
    void refusesUnknownSlot() throws Exception {
        String token = signInNewPlayer("eq_badslot", "eq_badslot@example.com");

        mockMvc.perform(post("/api/v1/player/equipment/WIZARD_HAT", token)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new EquipPayload(randomUuid()))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("refuses to equip a retired item")
    void refusesInactiveItem() throws Exception {
        String token = signInNewPlayer("eq_retired", "eq_retired@example.com");
        setCoins("eq_retired@example.com", 5000);
        purchase(token, itemId("ADAPTIVE_FIREWALL"));
        UUID row = ownedItem("eq_retired@example.com", "ADAPTIVE_FIREWALL").orElseThrow().getId();

        withItemRetired("ADAPTIVE_FIREWALL", () -> {
            try {
                mockMvc.perform(post("/api/v1/player/equipment/SECURITY", token)
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(new EquipPayload(row))))
                        .andExpect(status().isBadRequest());
                assertThat(slotItemCode(token, EquipmentSlot.SECURITY)).isNull();
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
    }

    @Test
    @DisplayName("unequips without removing the item from the inventory")
    void unequipKeepsItem() throws Exception {
        String token = signInNewPlayer("eq_off", "eq_off@example.com");
        setCoins("eq_off@example.com", 5000);
        purchase(token, itemId("BASIC_FIREWALL"));
        UUID row = ownedItem("eq_off@example.com", "BASIC_FIREWALL").orElseThrow().getId();
        equip(token, EquipmentSlot.SECURITY, row);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/player/equipment/SECURITY", token)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        assertThat(slotItemCode(token, EquipmentSlot.SECURITY)).isNull();
        assertThat(ownedItem("eq_off@example.com", "BASIC_FIREWALL")).isPresent();

        JsonNode firewall = findByCode(
                getData(token, "/api/v1/player/inventory").path("items"), "BASIC_FIREWALL");
        assertThat(firewall.path("equipped").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("unequipping an empty slot is not an error")
    void unequipEmptySlotIsIdempotent() throws Exception {
        String token = signInNewPlayer("eq_empty", "eq_empty@example.com");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/player/equipment/NETWORK", token)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an unequipped item can be equipped again")
    void canReequipAfterUnequip() throws Exception {
        String token = signInNewPlayer("eq_again", "eq_again@example.com");
        setCoins("eq_again@example.com", 5000);
        purchase(token, itemId("BASIC_FIREWALL"));
        UUID row = ownedItem("eq_again@example.com", "BASIC_FIREWALL").orElseThrow().getId();

        equip(token, EquipmentSlot.SECURITY, row);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/v1/player/equipment/SECURITY", token)
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        equip(token, EquipmentSlot.SECURITY, row);

        assertThat(slotItemCode(token, EquipmentSlot.SECURITY)).isEqualTo("BASIC_FIREWALL");
    }

    @Test
    @DisplayName("requires authentication for every equipment route")
    void requiresAuthentication() throws Exception {
        UUID row = randomUuid();
        mockMvc.perform(get0("/api/v1/player/equipment")).andExpect(status().isUnauthorized());
        mockMvc.perform(get0("/api/v1/player/inventory")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/player/equipment/MAIN_DEVICE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new EquipPayload(row))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/player/equipment/MAIN_DEVICE"))
                .andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder get0(String url) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url);
    }

    private JsonNode findByCode(JsonNode items, String code) {
        for (JsonNode item : items) {
            if (code.equals(item.path("code").asText())) {
                return item;
            }
        }
        throw new AssertionError("Item " + code + " was not present in the response");
    }

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