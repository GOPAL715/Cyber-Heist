package com.cyberheist.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cyberheist.IntegrationTestSupport;
import com.cyberheist.player.PlayerProfile;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Buying items.
 *
 * <p>The recurring theme is that the client only ever says "buy item X". Several
 * of these tests send a body full of values that would be devastating if any of
 * them were honoured, and assert that none of them changed the outcome.
 */
class PurchaseIntegrationTest extends IntegrationTestSupport {

    @Test
    @DisplayName("buys an item, charging the catalogue price and granting the item")
    void purchasesItem() throws Exception {
        String token = signInNewPlayer("buy_ok", "buy_ok@example.com");
        setCoins("buy_ok@example.com", 1000);

        UUID itemId = itemId("BASIC_PROCESSOR");
        long price = item("BASIC_PROCESSOR").getPrice();

        MvcResult result = mockMvc.perform(
                        authPost("/api/v1/player/shop/items/" + itemId + "/purchase", token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.code").value("BASIC_PROCESSOR"))
                .andExpect(jsonPath("$.data.pricePaid").value(price))
                .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        assertThat(data.path("coins").asLong()).isEqualTo(1000 - price);
        assertThat(ownedItem("buy_ok@example.com", "BASIC_PROCESSOR")).isPresent();
    }

    @Test
    @DisplayName("deducts exactly the server price and nothing else")
    void deductsServerDefinedPrice() throws Exception {
        String token = signInNewPlayer("buy_price", "buy_price@example.com");
        setCoins("buy_price@example.com", 1000);

        long before = profileOf("buy_price@example.com").getCoins();
        long price = item("NEURAL_PROCESSOR").getPrice();

        purchase(token, itemId("NEURAL_PROCESSOR"));

        assertThat(profileOf("buy_price@example.com").getCoins()).isEqualTo(before - price);
    }

    @Test
    @DisplayName("refuses to sell an item the player cannot afford, and charges nothing")
    void refusesWhenCoinsAreShort() throws Exception {
        String token = signInNewPlayer("buy_poor", "buy_poor@example.com");
        setCoins("buy_poor@example.com", 10);

        MvcResult result = purchaseExpectingFailure(token, itemId("NEURAL_PROCESSOR"));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).contains("Not enough coins");
        assertThat(profileOf("buy_poor@example.com").getCoins()).isEqualTo(10);
        assertThat(ownedItem("buy_poor@example.com", "NEURAL_PROCESSOR")).isEmpty();
    }

    @Test
    @DisplayName("refuses to sell a retired item")
    void refusesInactiveItem() throws Exception {
        String token = signInNewPlayer("buy_retired", "buy_retired@example.com");
        setCoins("buy_retired@example.com", 10000);

        withItemRetired("QUANTUM_PROCESSOR", () -> {
            try {
                MvcResult result = purchaseExpectingFailure(token, itemId("QUANTUM_PROCESSOR"));
                assertThat(result.getResponse().getStatus()).isEqualTo(400);
                assertThat(profileOf("buy_retired@example.com").getCoins()).isEqualTo(10000);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
    }

    @Test
    @DisplayName("404s an item that does not exist")
    void refusesUnknownItem() throws Exception {
        String token = signInNewPlayer("buy_ghost", "buy_ghost@example.com");

        assertThat(purchaseExpectingFailure(token, randomUuid()).getResponse().getStatus())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("409s a second purchase of an item already owned, without charging again")
    void refusesDuplicatePurchase() throws Exception {
        String token = signInNewPlayer("buy_dup", "buy_dup@example.com");
        setCoins("buy_dup@example.com", 5000);

        purchase(token, itemId("ADAPTIVE_FIREWALL"));
        long afterFirst = profileOf("buy_dup@example.com").getCoins();

        MvcResult second = purchaseExpectingFailure(token, itemId("ADAPTIVE_FIREWALL"));

        assertThat(second.getResponse().getStatus()).isEqualTo(409);
        // The decisive assertion: the failed attempt moved nothing.
        assertThat(profileOf("buy_dup@example.com").getCoins()).isEqualTo(afterFirst);
        assertThat(inventoryRepository.findByUserIdAndItemId(userIdOf("buy_dup@example.com"),
                itemId("ADAPTIVE_FIREWALL"))).isPresent();
    }

    @Test
    @DisplayName("409s rebuying the free starter laptop")
    void refusesRebuyingStarterItem() throws Exception {
        String token = signInNewPlayer("buy_starter", "buy_starter@example.com");

        // It costs nothing, so a naive implementation would happily "succeed".
        assertThat(purchaseExpectingFailure(token, itemId("BASIC_LAPTOP")).getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    @DisplayName("ignores a body that tries to set the price")
    void ignoresPriceTampering() throws Exception {
        String token = signInNewPlayer("buy_tamper", "buy_tamper@example.com");
        setCoins("buy_tamper@example.com", 1000);
        long price = item("STEALTH_LAPTOP").getPrice();

        MvcResult result = mockMvc.perform(authPost(
                        "/api/v1/player/shop/items/" + itemId("STEALTH_LAPTOP") + "/purchase", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":1,\"coins\":1000000,\"rarity\":\"LEGENDARY\",\"bonus\":999999}"))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        assertThat(data.path("pricePaid").asLong()).isEqualTo(price);
        assertThat(profileOf("buy_tamper@example.com").getCoins()).isEqualTo(1000 - price);
    }

    @Test
    @DisplayName("has no request body at all, so a quantity cannot be smuggled in")
    void hasNoPurchaseBody() throws Exception {
        String token = signInNewPlayer("buy_strict", "buy_strict@example.com");
        setCoins("buy_strict@example.com", 1000);
        long price = item("BASIC_PROCESSOR").getPrice();

        MvcResult result = mockMvc.perform(authPost(
                        "/api/v1/player/shop/items/" + itemId("BASIC_PROCESSOR") + "/purchase", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":99}"))
                .andReturn();

        // The handler declares no body parameter, so there is no DTO the fields
        // could bind to: the request is a plain "buy this item" and succeeds on
        // its own terms. What matters is that the body changed nothing - not the
        // price charged, and not the quantity granted.
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(profileOf("buy_strict@example.com").getCoins()).isEqualTo(1000 - price);
        assertThat(ownedItem("buy_strict@example.com", "BASIC_PROCESSOR").orElseThrow().getQuantity())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("requires authentication")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/player/shop/items/" + itemId("BASIC_PROCESSOR") + "/purchase"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("spends from the caller's own balance, not a named one")
    void spendsOnlyCallersBalance() throws Exception {
        String tokenA = signInNewPlayer("buyer_a", "buyer_a@example.com");
        registerPlayer("buyer_b", "buyer_b@example.com");
        setCoins("buyer_a@example.com", 1000);
        setCoins("buyer_b@example.com", 1000);

        purchase(tokenA, itemId("BASIC_PROCESSOR"));

        PlayerProfile untouched = profileOf("buyer_b@example.com");
        assertThat(untouched.getCoins()).isEqualTo(1000);
        assertThat(ownedItem("buyer_b@example.com", "BASIC_PROCESSOR")).isEmpty();
    }
}