package com.cyberheist.energy;

import com.cyberheist.IntegrationTestSupport;
import com.cyberheist.player.PlayerProfile;
import com.cyberheist.puzzle.PuzzleAttemptStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Energy regeneration as a player meets it: through the API, with the server
 * clock as the only input.
 *
 * <p>The arithmetic itself is covered in {@code EnergyServiceTest}. What these
 * cases add is everything around it - that a read refreshes and persists, that
 * a mission start charges the refreshed figure, and that two simultaneous
 * starts cannot both spend the same energy.
 *
 * <p>Every case registers its own account. The suite shares one in-memory
 * database, so a reused username would fail on the second test rather than on
 * the thing being tested.
 */
class EnergyRegenerationIntegrationTest extends IntegrationTestSupport {

    private static final String MISSION = "RECON_PERIMETER";

    @Test
    @DisplayName("a new player starts at full energy")
    void startsFull() throws Exception {
        String token = signInNewPlayer("energyfull", "energyfull@example.com");

        mockMvc.perform(authGet("/api/v1/player/profile", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.energy").value(100))
                .andExpect(jsonPath("$.data.energyMaximum").value(100))
                .andExpect(jsonPath("$.data.energyRegenerationEnabled").value(true))
                .andExpect(jsonPath("$.data.energyRegenerationAmount").value(1))
                .andExpect(jsonPath("$.data.energyRegenerationIntervalSeconds").value(300));
    }

    @Test
    @DisplayName("80 energy and five minutes gives 81 on the next read")
    void regeneratesOnePerInterval() throws Exception {
        String email = "energyone@example.com";
        String token = signInNewPlayer("energyone", email);
        setEnergy(email, 80);
        advanceEnergyClock(email, Duration.ofMinutes(5));

        mockMvc.perform(authGet("/api/v1/player/profile", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.energy").value(81));
    }

    @Test
    @DisplayName("regeneration accumulates across several intervals")
    void regeneratesAcrossIntervals() throws Exception {
        String email = "energyten@example.com";
        String token = signInNewPlayer("energyten", email);
        setEnergy(email, 70);
        advanceEnergyClock(email, Duration.ofMinutes(20));

        mockMvc.perform(authGet("/api/v1/player/profile", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.energy").value(74)); // 70 + four units
    }

    @Test
    @DisplayName("80 energy and a hundred minutes tops up to the cap")
    void capsAtMaximum() throws Exception {
        String email = "energycap@example.com";
        String token = signInNewPlayer("energycap", email);
        setEnergy(email, 80);
        advanceEnergyClock(email, Duration.ofMinutes(100));

        mockMvc.perform(authGet("/api/v1/player/profile", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.energy").value(100));

        // And it stays capped however much longer the player is away.
        advanceEnergyClock(email, Duration.ofDays(30));
        mockMvc.perform(authGet("/api/v1/player/profile", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.energy").value(100));
    }

    @Test
    @DisplayName("a mission start charges the refreshed balance, not a stale one")
    void missionStartUsesTheRefreshedBalance() throws Exception {
        String email = "energyspend@example.com";
        String token = signInNewPlayer("energyspend", email);
        var mission = missionRepository.findByCode(MISSION).orElseThrow();

        setEnergy(email, 80);
        advanceEnergyClock(email, Duration.ofMinutes(15));

        // 80 + 3 = 83 available, then the mission's cost is charged: 73.
        mockMvc.perform(authPost("/api/v1/player/missions/" + mission.getId() + "/start", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.player.energy").value(83 - mission.getEnergyCost()));

        assertThat(profileOf(email).getEnergy()).isEqualTo(83 - mission.getEnergyCost());
    }

    @Test
    @DisplayName("energy is never negative, whatever the player attempts")
    void neverGoesNegative() throws Exception {
        String email = "energydrained@example.com";
        String token = signInNewPlayer("energydrained", email);

        setEnergy(email, 0);

        mockMvc.perform(authPost("/api/v1/player/missions/" + missionId(MISSION) + "/start", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Not enough energy: this mission costs 10"));

        assertThat(profileOf(email).getEnergy()).isZero();

        // Regeneration from empty stays inside the cap.
        advanceEnergyClock(email, Duration.ofHours(24));
        mockMvc.perform(authGet("/api/v1/player/profile", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.energy").value(100));
    }

    @Test
    @DisplayName("regeneration is measured from the server's last accounting, not the client's")
    void serverTimeIsTheOnlyInput() throws Exception {
        String email = "energytime@example.com";
        String token = signInNewPlayer("energytime", email);
        setEnergy(email, 50);

        // Nothing has elapsed, so any number of profile reads must not invent
        // energy. A client counting down on its own clock and offering the
        // result would get a different answer, which is the point.
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(authGet("/api/v1/player/profile", token))
                    .andExpect(jsonPath("$.data.energy").value(50));
        }
        assertThat(profileOf(email).getEnergy()).isEqualTo(50);
    }

    @Test
    @DisplayName("a partial interval is carried forward rather than discarded")
    void partialIntervalsCarryForward() throws Exception {
        String email = "energycarry@example.com";
        String token = signInNewPlayer("energycarry", email);
        setEnergy(email, 50);

        // Four minutes is short of the five-minute interval, so nothing is earned.
        advanceEnergyClock(email, Duration.ofMinutes(4));
        mockMvc.perform(authGet("/api/v1/player/profile", token))
                .andExpect(jsonPath("$.data.energy").value(50));

        // Reading again must not restart the clock. If the stamp were reset to
        // "now" on every read, the next minute below would still be short of an
        // interval and the player would never regenerate at all.
        mockMvc.perform(authGet("/api/v1/player/profile", token))
                .andExpect(jsonPath("$.data.energy").value(50));

        advanceEnergyClock(email, Duration.ofMinutes(1));
        mockMvc.perform(authGet("/api/v1/player/profile", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.energy").value(51));
    }

    @Test
    @DisplayName("concurrent mission starts each pay their own energy cost")
    void concurrentStartsAreChargedIndividually() throws Exception {
        String email = "energyconcurrent@example.com";
        String token = signInNewPlayer("energyconcurrent", email);
        PlayerProfile before = profileOf(email);

        var mission = missionRepository.findByCode(MISSION).orElseThrow();
        int cost = mission.getEnergyCost();

        // Two simultaneous starts. Without a lock both could read the same
        // balance and both deduct from it, marking two attempts in progress
        // while charging for one.
        ConcurrentLinkedQueue<Integer> results = fireConcurrently(2, () ->
                mockMvc.perform(authPost("/api/v1/player/missions/" + mission.getId() + "/start", token))
                        .andReturn().getResponse().getStatus());

        assertThat(results).hasSize(2).allMatch(code -> code == 200);

        assertThat(profileOf(email).getEnergy())
                .as("both starts must be paid for")
                .isEqualTo(before.getEnergy() - 2 * cost);

        assertThat(progressRepository
                .findByUserIdAndMissionId(userIdOf(email), mission.getId())
                .orElseThrow()
                .getAttemptCount())
                .isEqualTo(2);

        // Both attempts left a puzzle behind: the first superseded, the second live.
        var puzzles = puzzleRepository.findByUserIdAndMissionIdOrderByAttemptNumberAsc(
                userIdOf(email), mission.getId());
        assertThat(puzzles).hasSize(2);
        assertThat(puzzles.get(0).getStatus()).isEqualTo(PuzzleAttemptStatus.EXPIRED);
        assertThat(puzzles.get(1).getStatus()).isEqualTo(PuzzleAttemptStatus.ACTIVE);
    }

    @Test
    @DisplayName("concurrent starts never overdraw a balance that cannot cover them all")
    void concurrentMutationsStayNonNegative() throws Exception {
        String email = "energyoverdraw@example.com";
        String token = signInNewPlayer("energyoverdraw", email);
        var mission = missionRepository.findByCode(MISSION).orElseThrow();

        // Enough energy for exactly three starts, so the rest must be refused
        // rather than allowed to overdraw.
        setEnergy(email, 3 * mission.getEnergyCost());

        ConcurrentLinkedQueue<Integer> results = fireConcurrently(8, () ->
                mockMvc.perform(authPost("/api/v1/player/missions/" + mission.getId() + "/start", token))
                        .andReturn().getResponse().getStatus());

        assertThat(profileOf(email).getEnergy())
                .isNotNegative()
                .isZero();
        assertThat(results).allMatch(code -> code == 200 || code == 400);
        assertThat(results.stream().filter(code -> code == 200).count())
                .as("exactly three starts can be afforded")
                .isEqualTo(3);
    }

    /**
     * Releases {@code threads} callers at once and collects their status codes.
     *
     * <p>A latch rather than a loop of sequential calls, because the property
     * under test only exists when the requests genuinely overlap.
     */
    private ConcurrentLinkedQueue<Integer> fireConcurrently(int threads, ThrowingCall call) {
        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<Integer> results = new ConcurrentLinkedQueue<>();
        var executor = Executors.newFixedThreadPool(threads);

        try {
            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    try {
                        startLine.await();
                        results.add(call.invoke());
                    } catch (Exception ex) {
                        results.add(-1);
                    } finally {
                        done.countDown();
                    }
                });
            }
            startLine.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS))
                    .as("all concurrent requests should finish")
                    .isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running concurrent requests", ex);
        } finally {
            executor.shutdownNow();
        }
        return results;
    }

    @FunctionalInterface
    private interface ThrowingCall {
        int invoke() throws Exception;
    }
}