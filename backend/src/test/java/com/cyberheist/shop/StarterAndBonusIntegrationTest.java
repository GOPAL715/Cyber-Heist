package com.cyberheist.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cyberheist.IntegrationTestSupport;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The free starter device and the bonuses equipment actually grants.
 *
 * <p>These tie the Phase 4 systems to the Phase 2 and 3 gameplay loop: a reward
 * that used to be a fixed number is now base plus equipment, and the arithmetic
 * happens on the server.
 */
class StarterAndBonusIntegrationTest extends IntegrationTestSupport {

    @Test
    @DisplayName("a new player is granted the free starter laptop")
    void newPlayerReceivesStarterItem() throws Exception {
        String email = "starter_new@example.com";
        registerPlayer("starter_new", email);

        // Free: it must not have cost the starting balance of 100 coins.
        assertThat(profileOf(email).getCoins()).isEqualTo(100);

        assertThat(ownedItem(email, "BASIC_LAPTOP")).isPresent();
    }

    @Test
    @DisplayName("the starter laptop is equipped, so the loadout is never empty")
    void starterItemIsEquipped() throws Exception {
        String token = signInNewPlayer("starter_worn", "starter_worn@example.com");

        assertThat(slotItemCode(token, EquipmentSlot.MAIN_DEVICE)).isEqualTo("BASIC_LAPTOP");
    }

    @Test
    @DisplayName("the starter item grants its bonus without being bought")
    void starterItemGrantsItsBonus() throws Exception {
        String token = signInNewPlayer("starter_bonus", "starter_bonus@example.com");

        JsonNode bonuses = getData(token, "/api/v1/player/equipment").path("bonuses");

        assertThat(bonusPercent(bonuses, "MISSION_SPEED")).isEqualTo(5);
    }

    @Test
    @DisplayName("a mission reward is the base amount plus the equipped bonus")
    void bonusRaisesMissionReward() throws Exception {
        String email = "bonus_xp@example.com";
        String token = signInNewPlayer("bonus_xp", email);

        // The starter laptop grants mission speed, not XP, so the baseline
        // reward is untouched until a processor is equipped.
        UUID target = missionId("RECON_PERIMETER");
        long baseXp = missionRepository.findById(target).orElseThrow().getXpReward();

        setCoins(email, 5000);
        purchase(token, itemId("NEURAL_PROCESSOR"));
        UUID row = ownedItem(email, "NEURAL_PROCESSOR").orElseThrow().getId();
        equip(token, EquipmentSlot.PROCESSOR, row);

        JsonNode bonuses = getData(token, "/api/v1/player/equipment").path("bonuses");
        assertThat(bonusPercent(bonuses, "EXPERIENCE_BONUS")).isEqualTo(10);

        JsonNode result = completeMissionThroughPuzzle(token, target, email);

        // 50 base XP at +10% is 55, using the server's own rounding rule.
        assertThat(result.path("rewards").path("experience").asLong())
                .isEqualTo(EquipmentBonusService.applyPercentBonus(baseXp, 10))
                .isEqualTo(55);
    }

    @Test
    @DisplayName("unequipping removes the bonus from the next reward")
    void unequippingRemovesTheBonus() throws Exception {
        String email = "bonus_off@example.com";
        String token = signInNewPlayer("bonus_off", email);
        setCoins(email, 5000);

        purchase(token, itemId("NEURAL_PROCESSOR"));
        UUID row = ownedItem(email, "NEURAL_PROCESSOR").orElseThrow().getId();
        equip(token, EquipmentSlot.PROCESSOR, row);
        assertThat(bonusPercent(getData(token, "/api/v1/player/equipment").path("bonuses"),
                "EXPERIENCE_BONUS")).isEqualTo(10);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/player/equipment/PROCESSOR", token)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        JsonNode bonuses = getData(token, "/api/v1/player/equipment").path("bonuses");
        assertThat(findBonus(bonuses, "EXPERIENCE_BONUS")).isNull();
    }

    @Test
    @DisplayName("energy efficiency shortens a mission cost, deterministically")
    void energyEfficiencyDiscountsMissionCost() throws Exception {
        String email = "bonus_energy@example.com";
        String token = signInNewPlayer("bonus_energy", email);

        // A 30-energy mission, so a 5% discount is visible after rounding.
        // A 10-energy mission would round back to 10 and prove nothing.
        grantExperience(email, 2000);
        UUID target = missionId("CRYPTO_SECRET");
        int baseCost = missionRepository.findById(target).orElseThrow().getEnergyCost();

        // Basic Firewall grants +5% energy efficiency.
        setCoins(email, 5000);
        purchase(token, itemId("BASIC_FIREWALL"));
        UUID row = ownedItem(email, "BASIC_FIREWALL").orElseThrow().getId();
        equip(token, EquipmentSlot.SECURITY, row);

        assertThat(bonusPercent(getData(token, "/api/v1/player/equipment").path("bonuses"),
                "ENERGY_EFFICIENCY")).isEqualTo(5);

        int charged = EquipmentBonusService.applyEnergyDiscount(baseCost, 5);
        int before = profileOf(email).getEnergy();
        startMission(token, "CRYPTO_SECRET");

        assertThat(before - profileOf(email).getEnergy())
                .as("the server should charge the discounted cost")
                .isEqualTo(charged);
        // Cheaper than base, but never free.
        assertThat(charged).isLessThan(baseCost).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("a request cannot inject a bonus into the loadout")
    void bonusesComeFromTheCatalogueOnly() throws Exception {
        String token = signInNewPlayer("bonus_fake", "bonus_fake@example.com");

        mockMvc.perform(authPost("/api/v1/player/equipment/PROCESSOR", token)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"inventoryItemId\":\"" + randomUuid() + "\",\"bonus\":999999,\"rarity\":\"LEGENDARY\"}"))
                .andExpect(status().isNotFound());

        JsonNode bonuses = getData(token, "/api/v1/player/equipment").path("bonuses");
        assertThat(bonuses).allSatisfy(bonus ->
                assertThat(bonus.path("percent").asInt()).isLessThanOrEqualTo(
                        EquipmentBonusService.MAX_MISSION_SPEED + 50));
        assertThat(findBonus(bonuses, "EXPERIENCE_BONUS")).isNull();
    }

    private int bonusPercent(JsonNode bonuses, String type) {
        Integer value = findBonus(bonuses, type);
        assertThat(value).as("bonus %s should be present", type).isNotNull();
        return value;
    }

    private Integer findBonus(JsonNode bonuses, String type) {
        for (JsonNode bonus : bonuses) {
            if (type.equals(bonus.path("type").asText())) {
                return bonus.path("percent").asInt();
            }
        }
        return null;
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