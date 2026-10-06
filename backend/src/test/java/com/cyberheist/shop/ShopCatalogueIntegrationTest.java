package com.cyberheist.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cyberheist.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reading the item catalogue.
 *
 * <p>Covers the rule that the catalogue is server-owned: prices and rarities are
 * whatever the seeded rows say, inactive items are invisible, and nothing about
 * the response depends on who is asking beyond their own owned flags.
 */
class ShopCatalogueIntegrationTest extends IntegrationTestSupport {

    @Test
    @DisplayName("returns the active catalogue, cheapest first")
    void listsActiveItems() throws Exception {
        String token = signInNewPlayer("shop_catalogue", "shop_catalogue@example.com");

        JsonNode data = getData(token, "/api/v1/player/shop");
        JsonNode items = data.path("items");

        assertThat(items).isNotEmpty();
        assertThat(items).allSatisfy(item ->
                assertThat(item.path("code").asText()).isNotBlank());

        // Ordering is cheapest first, so the starter laptop leads.
        assertThat(items.get(0).path("code").asText()).isEqualTo("BASIC_LAPTOP");

        long previous = Long.MIN_VALUE;
        for (JsonNode item : items) {
            long price = item.path("price").asLong();
            assertThat(price).isGreaterThanOrEqualTo(previous);
            previous = price;
        }
    }

    @Test
    @DisplayName("prices come from the catalogue, never from the request")
    void pricesAreServerDefined() throws Exception {
        String token = signInNewPlayer("price_player", "price@example.com");

        JsonNode items = getData(token, "/api/v1/player/shop").path("items");

        JsonNode neural = findByCode(items, "NEURAL_PROCESSOR");
        assertThat(neural.path("price").asLong()).isEqualTo(item("NEURAL_PROCESSOR").getPrice());
        assertThat(neural.path("rarity").asText()).isEqualTo("RARE");
    }

    @Test
    @DisplayName("exposes each item's effect definition")
    void exposesEffects() throws Exception {
        String token = signInNewPlayer("effect_player", "effect@example.com");

        JsonNode items = getData(token, "/api/v1/player/shop").path("items");
        JsonNode neural = findByCode(items, "NEURAL_PROCESSOR");

        assertThat(neural.path("effects")).hasSize(1);
        assertThat(neural.path("effects").get(0).path("type").asText()).isEqualTo("EXPERIENCE_BONUS");
        assertThat(neural.path("effects").get(0).path("value").asInt()).isEqualTo(10);
    }

    @Test
    @DisplayName("hides inactive items from the catalogue")
    void hidesInactiveItems() throws Exception {
        String token = signInNewPlayer("shop_inactive", "shop_inactive@example.com");

        withItemRetired("BASIC_FIREWALL", () -> {
            try {
                JsonNode items = getData(token, "/api/v1/player/shop").path("items");
                assertThat(findByCode(items, "BASIC_FIREWALL")).isNull();
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
    }

    @Test
    @DisplayName("returns one item's server-defined detail")
    void returnsItemDetail() throws Exception {
        String token = signInNewPlayer("shop_detail", "shop_detail@example.com");

        mockMvc.perform(authGet("/api/v1/player/shop/items/" + itemId("ADAPTIVE_FIREWALL"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("ADAPTIVE_FIREWALL"))
                .andExpect(jsonPath("$.data.category").value("SECURITY"))
                .andExpect(jsonPath("$.data.rarity").value("UNCOMMON"))
                .andExpect(jsonPath("$.data.price").value(220));
    }

    @Test
    @DisplayName("404s an item id that does not exist")
    void unknownItemIsNotFound() throws Exception {
        String token = signInNewPlayer("shop_ghost", "shop_ghost@example.com");

        mockMvc.perform(authGet("/api/v1/player/shop/items/" + randomUuid(), token))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("refuses to detail an inactive item")
    void inactiveItemDetailIsUnavailable() throws Exception {
        String token = signInNewPlayer("retired_player", "retired@example.com");

        withItemRetired("QUANTUM_CORE", () -> {
            try {
                mockMvc.perform(authGet("/api/v1/player/shop/items/" + itemId("QUANTUM_CORE"), token))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.message").value("This item is not available"));
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
    }

    @Test
    @DisplayName("requires authentication")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/player/shop")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/player/shop/items/" + itemId("NEURAL_PROCESSOR")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("marks the starter laptop as owned for a new player")
    void marksStarterItemOwned() throws Exception {
        String token = signInNewPlayer("starterflag_player", "starterflag@example.com");

        JsonNode items = getData(token, "/api/v1/player/shop").path("items");

        assertThat(findByCode(items, "BASIC_LAPTOP").path("owned").asBoolean()).isTrue();
        assertThat(findByCode(items, "NEURAL_PROCESSOR").path("owned").asBoolean()).isFalse();
    }

    private static JsonNode findByCode(JsonNode items, String code) {
        for (JsonNode item : items) {
            if (code.equals(item.path("code").asText())) {
                return item;
            }
        }
        return null;
    }
}