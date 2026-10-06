package com.cyberheist.shop;

import static org.assertj.core.api.Assertions.assertThat;

import com.cyberheist.IntegrationTestSupport;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Concurrent purchases and equips.
 *
 * <p>The scenario that matters economically: a player with 800 coins fires two
 * requests at once for items costing 750 and 550. 1300 coins of intent against
 * 800 coins of balance must yield exactly one purchase, and the balance must
 * reflect only that one.
 *
 * <p>Both are safe because every mutating equipment and purchase path takes a
 * pessimistic write lock on the player's profile row before reading the balance
 * or the loadout. The requests serialise, so the second observes the first's
 * effect. Without that lock both would see 800 coins, both would succeed, and
 * the balance would go negative.
 *
 * <p>The assertions are on resulting state rather than on status codes alone,
 * because a status code cannot tell you whether the coins came out twice.
 */
class ConcurrentPurchaseIntegrationTest extends IntegrationTestSupport {

    private static final long COINS = 800;

    @Autowired
    private PlayerEquipmentRepository equipmentRepository;

    private ExecutorService executor;

    @AfterEach
    void shutdownExecutor() throws Exception {
        if (executor != null) {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("two simultaneous purchases cannot both spend the same balance")
    void concurrentPurchasesCannotOverdraw() throws Exception {
        String email = "race_rich@example.com";
        String token = signInNewPlayer("race_rich", email);
        setCoins(email, COINS);

        List<Integer> statuses = runTogether(
                () -> purchaseExpectingFailure(token, itemId("NEURAL_PROCESSOR")).getResponse().getStatus(),
                () -> purchaseExpectingFailure(token, itemId("STEALTH_LAPTOP")).getResponse().getStatus());

        assertThat(statuses).filteredOn(status -> status == 201)
                .as("one purchase of 750 plus one of 550 must not both clear against 800 coins: %s", statuses)
                .hasSize(1);

        // Exactly one purchased row beyond the starter laptop, and the balance
        // reflects one price only.
        assertThat(purchasedRows(email)).hasSize(1);

        long expected = ownedItem(email, "NEURAL_PROCESSOR").isPresent()
                ? COINS - item("NEURAL_PROCESSOR").getPrice()
                : COINS - item("STEALTH_LAPTOP").getPrice();
        assertThat(profileOf(email).getCoins()).isEqualTo(expected);
        assertThat(profileOf(email).getCoins()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("two simultaneous purchases of the same item grant exactly one copy")
    void concurrentDuplicatePurchaseGrantsOne() throws Exception {
        String email = "race_dup@example.com";
        String token = signInNewPlayer("race_dup", email);
        setCoins(email, COINS);

        UUID target = itemId("NEURAL_PROCESSOR");

        List<Integer> statuses = runTogether(
                () -> purchaseExpectingFailure(token, target).getResponse().getStatus(),
                () -> purchaseExpectingFailure(token, target).getResponse().getStatus());

        // The loser is told it already owns the item, not that it was short of coins.
        assertThat(statuses).contains(201);
        assertThat(statuses.stream().filter(status -> status == 201).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(status -> status == 409).count()).isEqualTo(1);

        // Charged exactly once.
        assertThat(profileOf(email).getCoins()).isEqualTo(COINS - item("NEURAL_PROCESSOR").getPrice());
        assertThat(inventoryRepository.findByUserId(userIdOf(email)))
                .filteredOn(row -> row.getItemId().equals(target))
                .hasSize(1);
    }

    @Test
    @DisplayName("two simultaneous equips of different items into one slot leave one winner")
    void concurrentEquipsLeaveOneSlotOccupant() throws Exception {
        String email = "race_slot@example.com";
        String token = signInNewPlayer("race_slot", email);
        setCoins(email, 5000);

        purchase(token, itemId("BASIC_FIREWALL"));      // SECURITY, 60
        purchase(token, itemId("ADAPTIVE_FIREWALL"));   // SECURITY, 220
        UUID basic = ownedItem(email, "BASIC_FIREWALL").orElseThrow().getId();
        UUID adaptive = ownedItem(email, "ADAPTIVE_FIREWALL").orElseThrow().getId();

        List<Integer> statuses = runTogether(
                () -> equipStatus(token, EquipmentSlot.SECURITY, basic),
                () -> equipStatus(token, EquipmentSlot.SECURITY, adaptive));

        assertThat(statuses).allMatch(status -> status == 200);

        // Whatever the interleaving, the slot holds exactly one item and the
        // other was never left equipped somewhere as well.
        List<PlayerEquipment> security = equipmentRepository.findByUserId(userIdOf(email)).stream()
                .filter(row -> row.getSlot() == EquipmentSlot.SECURITY)
                .toList();
        assertThat(security).hasSize(1);
        assertThat(equipmentRepository.findByUserId(userIdOf(email)))
                .extracting(PlayerEquipment::getInventoryItemId)
                .doesNotHaveDuplicates();
    }

    private int equipStatus(String token, EquipmentSlot slot, UUID inventoryItemId) throws Exception {
        return equip(token, slot, inventoryItemId) != null ? 200 : 500;
    }

    /**
     * Inventory rows other than the free starter laptop.
     *
     * <p>Every player already owns one item, so counting raw rows would conflate
     * the starting grant with the purchases under test.
     */
    private List<PlayerInventoryItem> purchasedRows(String email) {
        UUID starter = itemId("BASIC_LAPTOP");
        return inventoryRepository.findByUserId(userIdOf(email)).stream()
                .filter(row -> !row.getItemId().equals(starter))
                .toList();
    }

    /**
     * Runs both calls at once and returns their results.
     *
     * <p>The latch is what makes this a real race: both threads are released
     * before either has necessarily reached the database, so the requests
     * genuinely overlap instead of being serialised by the harness.
     */
    private <T> List<T> runTogether(Callable<T> first, Callable<T> second) throws Exception {
        executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        Future<T> a = executor.submit(awaited(ready, go, first));
        Future<T> b = executor.submit(awaited(ready, go, second));

        assertThat(ready.await(10, TimeUnit.SECONDS)).as("both threads should start").isTrue();
        go.countDown();

        return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
    }

    private <T> Callable<T> awaited(CountDownLatch ready, CountDownLatch go, Callable<T> call) {
        return () -> {
            ready.countDown();
            go.await();
            return call.call();
        };
    }
}