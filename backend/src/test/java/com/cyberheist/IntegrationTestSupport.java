package com.cyberheist;

import com.cyberheist.mission.Mission;
import com.cyberheist.mission.MissionProgressRepository;
import com.cyberheist.mission.MissionRepository;
import com.cyberheist.boss.Boss;
import com.cyberheist.boss.BossEncounter;
import com.cyberheist.boss.BossEncounterRepository;
import com.cyberheist.boss.BossRepository;
import com.cyberheist.boss.EncounterStatus;
import com.cyberheist.player.PlayerProfile;
import com.cyberheist.player.PlayerProfileRepository;
import com.cyberheist.puzzle.PuzzleAttempt;
import com.cyberheist.puzzle.PuzzleAttemptRepository;
import com.cyberheist.puzzle.PuzzleService;
import com.cyberheist.shop.EquipmentSlot;
import com.cyberheist.shop.Item;
import com.cyberheist.shop.ItemRepository;
import com.cyberheist.shop.PlayerInventoryItem;
import com.cyberheist.shop.PlayerInventoryRepository;
import com.cyberheist.skill.Skill;
import com.cyberheist.skill.SkillRepository;
import com.cyberheist.user.Role;
import com.cyberheist.user.User;
import com.cyberheist.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared plumbing for the integration tests: a booted application context, a
 * MockMvc, and helpers for registering and signing in test players.
 */
@AutoConfigureMockMvc
@SpringBootTest
@ActiveProfiles("test")
public abstract class IntegrationTestSupport {

    protected static final String VALID_PASSWORD = "Str0ng!Passw0rd";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @Autowired
    protected MissionRepository missionRepository;

    @Autowired
    protected PlayerProfileRepository profileRepository;

    @Autowired
    protected ItemRepository itemRepository;

    @Autowired
    protected PlayerInventoryRepository inventoryRepository;

    @Autowired
    protected SkillRepository skillRepository;

    @Autowired
    protected BossRepository bossRepository;

    @Autowired
    protected BossEncounterRepository bossEncounterRepository;

    @Autowired
    protected com.cyberheist.progression.ProgressionService progressionService;

    @Autowired
    protected MissionProgressRepository progressRepository;

    @Autowired
    protected PuzzleAttemptRepository puzzleRepository;

    /**
     * The puzzle engine itself.
     *
     * <p>Tests use it the way the server does - re-deriving an answer from the
     * stored seed - so a test that wants to succeed has to go through exactly
     * the validation path a player's correct answer takes. Nothing here reaches
     * into the database to learn the answer, because nothing can: it is not
     * stored.
     */
    @Autowired
    protected PuzzleService puzzleService;

    @Autowired
    protected com.cyberheist.progression.LevelCurve levelCurve;

    protected UserRepository userRepository() {
        return userRepository;
    }

    /**
     * Registers a player through the real endpoint and returns the created user.
     *
     * <p>Going through the API rather than the repository keeps these tests
     * honest: they exercise the same path a real client uses.
     */
    protected User registerPlayer(String username, String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegistrationPayload(
                                username, email, VALID_PASSWORD))))
                .andReturn();

        if (result.getResponse().getStatus() != 201) {
            throw new IllegalStateException("Registration failed: " + result.getResponse().getContentAsString());
        }
        return userRepository.findByEmailIgnoreCase(email).orElseThrow();
    }

    /** Registers a player, disables it, and returns it. Used for disabled-login tests. */
    protected User registerDisabledPlayer(String username, String email) throws Exception {
        User user = registerPlayer(username, email);
        user.setEnabled(false);
        return userRepository.save(user);
    }

    /** Logs in and returns the raw JSON body. */
    protected JsonNode loginAndGetBody(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginPayload(email, password))))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** Logs in and returns the access token. */
    protected String loginAndGetAccessToken(String email, String password) throws Exception {
        JsonNode body = loginAndGetBody(email, password);
        JsonNode data = body.path("data");
        if (data.isMissingNode()) {
            throw new IllegalStateException("Login failed: " + body);
        }
        return data.path("accessToken").asText();
    }

    protected UUID randomUuid() {
        return UUID.randomUUID();
    }

    /** Stable id of a seeded mission, addressed by its catalogue code. */
    protected UUID missionId(String code) {
        return missionRepository.findByCode(code)
                .map(Mission::getId)
                .orElseThrow(() -> new IllegalStateException("Seeded mission not found: " + code));
    }

    /** Creates and logs in a player, returning a ready-to-use bearer token. */
    protected String signInNewPlayer(String username, String email) throws Exception {
        registerPlayer(username, email);
        return loginAndGetAccessToken(email, VALID_PASSWORD);
    }

    /** Performs a bearer-authenticated call. */
    protected MockHttpServletRequestBuilder authGet(String url, String token) {
        return get(url).header("Authorization", "Bearer " + token);
    }

    protected MockHttpServletRequestBuilder authPost(String url, String token) {
        return post(url).header("Authorization", "Bearer " + token);
    }

    /** Current persisted state of a player, for asserting on side effects. */
    protected PlayerProfile profileOf(String email) {
        UUID userId = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow()
                .getId();
        return profileRepository.findByUserId(userId).orElseThrow();
    }

    /** Creates an account directly, bypassing the API. */
    protected User createUserDirectly(String username, String email, String rawPassword, Role role) {
        User user = new User(UUID.randomUUID(), username, email.toLowerCase(),
                passwordEncoder.encode(rawPassword), role);
        return userRepository.saveAndFlush(user);
    }

    // ---------------------------------------------------------------------
    // Puzzle helpers
    //
    // Phase 3 made the puzzle, not the completion endpoint, the thing that
    // finishes a mission. Tests that care about rewards or progression now
    // drive the real loop - start, derive the answer server-side, submit -
    // rather than asserting against an endpoint that can no longer pay out.
    // ---------------------------------------------------------------------

    /** Starts a mission and returns the full start response, puzzle included. */
    protected JsonNode startMission(String token, String missionCode) throws Exception {
        UUID id = missionId(missionCode);
        MvcResult result = mockMvc.perform(authPost("/api/v1/player/missions/" + id + "/start", token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    /** The puzzle most recently generated for a player on a mission. */
    protected PuzzleAttempt latestPuzzle(String email, UUID missionId) {
        UUID userId = userRepository.findByEmailIgnoreCase(email).orElseThrow().getId();
        List<PuzzleAttempt> history =
                puzzleRepository.findByUserIdAndMissionIdOrderByAttemptNumberAsc(userId, missionId);
        assertThat(history).as("a puzzle should have been generated").isNotEmpty();
        return history.get(history.size() - 1);
    }

    /**
     * The answer the server expects, re-derived from the stored seed.
     *
     * <p>This is the test equivalent of a player who solves the puzzle. It uses
     * the same {@code PuzzleService} path the submission endpoint uses, so a
     * provider that stopped being deterministic would fail here rather than
     * quietly passing with a stale answer.
     */
    protected String correctAnswerFor(PuzzleAttempt puzzle) {
        return puzzleService.regenerate(puzzle.getPuzzleType(), puzzle.getDifficulty(), puzzle.getSeed())
                .challenge()
                .expectedAnswer();
    }

    /** Submits an answer through the real endpoint and returns the parsed body. */
    protected JsonNode submitPuzzle(String token, UUID missionId, UUID puzzleId, String answer)
            throws Exception {
        MvcResult result = mockMvc.perform(
                        authPost("/api/v1/player/missions/" + missionId + "/puzzle/submit", token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(new PuzzlePayload(puzzleId, answer))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    /**
     * Plays a mission to completion: start if needed, then answer the live
     * puzzle correctly.
     *
     * <p>Used by tests whose subject is rewards, progression or ownership rather
     * than the puzzle itself.
     *
     * @return the submission response body
     */
    protected JsonNode completeMissionThroughPuzzle(String token, UUID missionId, String email)
            throws Exception {
        var progress = progressRepository
                .findByUserIdAndMissionId(userIdOf(email), missionId).orElse(null);
        if (progress == null || progress.getStatus() != com.cyberheist.mission.MissionStatus.IN_PROGRESS) {
            mockMvc.perform(authPost("/api/v1/player/missions/" + missionId + "/start", token))
                    .andExpect(status().isOk());
        }
        PuzzleAttempt puzzle = latestPuzzle(email, missionId);
        return submitPuzzle(token, missionId, puzzle.getPuzzleId(), correctAnswerFor(puzzle));
    }

    /** Convenience overload that resolves the mission by its catalogue code. */
    protected JsonNode completeMissionThroughPuzzle(String token, String missionCode, String email)
            throws Exception {
        return completeMissionThroughPuzzle(token, missionId(missionCode), email);
    }

    /**
     * Places a live puzzle's window in the past.
     *
     * <p>The puzzle keeps its ACTIVE state, so a later submission takes the
     * expiry branch rather than the already-answered branch - which is the point,
     * since only the first proves that the server's clock decides.
     */
    protected void expirePuzzleWindow(java.util.UUID puzzleId) {
        java.time.Instant started = java.time.Instant.now().minus(java.time.Duration.ofMinutes(10));
        assertThat(puzzleRepository.rewindow(puzzleId, started, started.plusSeconds(60)))
                .as("the puzzle row should have been re-windowed")
                .isEqualTo(1);
    }

    protected UUID userIdOf(String email) {
        return userRepository.findByEmailIgnoreCase(email).orElseThrow().getId();
    }

    /**
     * Simulates offline time by moving a profile's energy clock into the past.
     *
     * <p>The amount is added to whatever the clock already reads, so successive
     * calls accumulate the way real elapsed time would. Setting it relative to
     * "now" every time would quietly discard the previous advance, and a test
     * written against that would pass while the feature was broken.
     */
    protected void advanceEnergyClock(String email, java.time.Duration elapsed) {
        PlayerProfile profile = profileOf(email);
        java.time.Instant current = profile.getLastEnergyUpdate();
        profile.setLastEnergyUpdate(
                (current == null ? java.time.Instant.now() : current).minus(elapsed));
        profileRepository.saveAndFlush(profile);
    }

    /**
     * Sets a player's energy balance directly.
     *
     * <p>{@link PlayerProfile} deliberately exposes no setter for energy,
     * because production code must go through {@code spendEnergy} and the
     * regeneration service. A test needs to set up a balance, though, and would
     * otherwise have to spend down to zero and play for hours to get there - so
     * it writes the field reflectively rather than opening a hole in the entity.
     */
    protected void setEnergy(String email, int energy) {
        PlayerProfile profile = profileOf(email);
        try {
            java.lang.reflect.Field field = PlayerProfile.class.getDeclaredField("energy");
            field.setAccessible(true);
            field.set(profile, energy);
            profileRepository.saveAndFlush(profile);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Unable to set the energy balance in a test", ex);
        }
        assertThat(profileOf(email).getEnergy()).isEqualTo(energy);
    }

    /**
     * Grants XP so a level-gated mission becomes startable.
     *
     * <p>Used only where the case under test needs a mission at a given tier;
     * level gating itself is covered by the Phase 2 progression tests.
     *
     * <p>Writes the field directly and so grants <em>no</em> skill points. Tests
     * about skill points must use {@link #applyExperience}, which goes through
     * the real {@code ProgressionService} path that awards them.
     */
    protected void grantExperience(String email, long xp) {
        PlayerProfile profile = profileOf(email);
        profile.addExperience(xp, levelCurve);
        profileRepository.saveAndFlush(profile);
    }

    /**
     * Awards XP through {@code ProgressionService}, so skill points are granted
     * exactly as they are in production.
     *
     * <p>This is the only way a test should create skill points from XP, and it
     * is what makes the multi-level-up rule testable: one call that crosses
     * several thresholds has to award a point per level.
     *
     * @return the progression result, so a test can assert levels gained
     */
    protected com.cyberheist.progression.ProgressionResult applyExperience(String email, long xp) {
        PlayerProfile profile = profileOf(email);
        var result = progressionService.awardExperience(profile, xp);
        profileRepository.saveAndFlush(profile);
        return result;
    }

    /** Sets the unspent skill point balance directly, for setup only. */
    protected void setSkillPoints(String email, int points) {
        PlayerProfile profile = profileOf(email);
        try {
            java.lang.reflect.Field field = PlayerProfile.class.getDeclaredField("skillPoints");
            field.setAccessible(true);
            field.set(profile, points);
            profileRepository.saveAndFlush(profile);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Unable to set the skill point balance in a test", ex);
        }
        assertThat(profileOf(email).getSkillPoints()).isEqualTo(points);
    }

    /** Stable id of a seeded skill, addressed by its catalogue code. */
    protected UUID skillId(String code) {
        return skillRepository.findByCode(code)
                .map(Skill::getId)
                .orElseThrow(() -> new IllegalStateException("Seeded skill not found: " + code));
    }

    /** The caller's skill tree, as {@code GET /player/skills} returns it. */
    protected JsonNode skillTree(String token) throws Exception {
        return getData(token, "/api/v1/player/skills");
    }

    /**
     * Unlocks the next level of a skill through the real endpoint.
     *
     * @throws AssertionError if the call did not return 200
     */
    protected JsonNode unlockSkill(String token, UUID skillId) throws Exception {
        MvcResult result = mockMvc.perform(authPost("/api/v1/player/skills/" + skillId + "/unlock", token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    /** Unlocks without asserting the status, so failure cases can be driven. */
    protected MvcResult unlockSkillExpectingFailure(String token, UUID skillId) throws Exception {
        return mockMvc.perform(authPost("/api/v1/player/skills/" + skillId + "/unlock", token))
                .andReturn();
    }

    /** Finds one skill's view inside a skill tree response. */
    protected JsonNode findSkill(JsonNode tree, String code) {
        for (JsonNode branch : tree.path("branches")) {
            for (JsonNode skill : branch.path("skills")) {
                if (code.equals(skill.path("code").asText())) {
                    return skill;
                }
            }
        }
        throw new AssertionError("Skill " + code + " was not present in the tree");
    }

    // ---------------------------------------------------------------------
    // Boss helpers
    //
    // Boss fights need a player level high enough to clear a gate, which no
    // amount of test-side reflection should pretend to earn. These drive the
    // real reward path so the level is genuinely reached.
    // ---------------------------------------------------------------------

    /** Stable id of a seeded boss, addressed by its catalogue code. */
    protected UUID bossId(String code) {
        return bossRepository.findByCode(code)
                .map(Boss::getId)
                .orElseThrow(() -> new IllegalStateException("Seeded boss not found: " + code));
    }

    /**
     * Retires a boss, then restores it.
     *
     * <p>{@link Boss} exposes no mutators because in production the catalogue is
     * written by migration alone. A test needs a retired boss to prove the board
     * hides it, so the flag is written reflectively — the same approach Phase 4
     * uses for retiring an item.
     *
     * <p>Restoring in a finally block matters: every integration test class shares
     * one in-memory database, so a boss left retired would break later tests
     * that happen to use it.
     */
    protected void withBossRetired(String code, Runnable assertions) {
        setBossActive(code, false);
        try {
            assertions.run();
        } finally {
            setBossActive(code, true);
        }
    }

    private void setBossActive(String code, boolean active) {
        Boss boss = bossRepository.findByCode(code)
                .orElseThrow(() -> new IllegalStateException("Seeded boss not found: " + code));
        try {
            java.lang.reflect.Field field = Boss.class.getDeclaredField("active");
            field.setAccessible(true);
            field.set(boss, active);
            bossRepository.saveAndFlush(boss);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Unable to change a boss's active flag in a test", ex);
        }
        assertThat(bossRepository.findByCode(code).orElseThrow().isActive())
                .as("boss %s active flag", code).isEqualTo(active);
    }

    /** Levels a player up through the real progression path. */
    protected void levelUpTo(String email, int targetLevel) {
        int current = profileOf(email).getLevel();
        if (current >= targetLevel) {
            return;
        }
        // A single award large enough to cross every remaining threshold, so the
        // level and the skill points that come with it are both genuine.
        long required = levelCurve.xpRequiredFor(targetLevel);
        applyExperience(email, Math.max(0, required - profileOf(email).getExperience()));
        assertThat(profileOf(email).getLevel())
                .as("player should have reached level %d", targetLevel)
                .isGreaterThanOrEqualTo(targetLevel);
    }

    /** Starts a boss encounter through the real endpoint. */
    protected JsonNode startBoss(String token, UUID bossId) throws Exception {
        MvcResult result = mockMvc.perform(
                        authPost("/api/v1/player/bosses/" + bossId + "/start", token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    /** Starts a boss without asserting the status, so failure cases can run. */
    protected MvcResult startBossExpectingFailure(String token, UUID bossId) throws Exception {
        return mockMvc.perform(authPost("/api/v1/player/bosses/" + bossId + "/start", token))
                .andReturn();
    }

    /** The caller's live boss encounter. */
    protected JsonNode currentEncounter(String token) throws Exception {
        return getData(token, "/api/v1/player/boss/encounter");
    }

    /**
     * The answer the server expects for the encounter's live puzzle.
     *
     * <p>Re-derived from the stored seed through {@code PuzzleService}, exactly
     * as the submission path does. The API never sends an answer, so a test that
     * wants to win has to solve it the way a player does.
     */
    protected String correctBossAnswer(String token, String email) {
        UUID userId = userIdOf(email);
        BossEncounter encounter = bossEncounterRepository
                .findByUserIdAndStatus(userId, EncounterStatus.ACTIVE)
                .orElseThrow(() -> new AssertionError("no active boss encounter"));
        PuzzleAttempt puzzle = puzzleRepository
                .findByBossEncounterIdAndAttemptNumber(encounter.getId(), encounter.getCurrentStage())
                .orElseThrow(() -> new AssertionError("no live puzzle for the current stage"));
        return puzzleService.regenerate(puzzle.getPuzzleType(), puzzle.getDifficulty(), puzzle.getSeed())
                .challenge()
                .expectedAnswer();
    }

    /**
     * Submits an answer to the encounter's live puzzle.
     *
     * @throws AssertionError if the call did not return 200
     */
    protected JsonNode submitBossStage(String token, String email, String answer) throws Exception {
        UUID userId = userIdOf(email);
        BossEncounter encounter = bossEncounterRepository
                .findByUserIdAndStatus(userId, EncounterStatus.ACTIVE)
                .orElseThrow(() -> new AssertionError("no active boss encounter"));
        PuzzleAttempt puzzle = puzzleRepository
                .findByBossEncounterIdAndAttemptNumber(encounter.getId(), encounter.getCurrentStage())
                .orElseThrow(() -> new AssertionError("no live puzzle for the current stage"));

        MvcResult result = mockMvc.perform(
                        authPost("/api/v1/player/boss/encounter/stage/submit", token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(new BossPayload(
                                        puzzle.getPuzzleId(), answer))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    /** Plays a boss encounter to victory, answering every phase correctly. */
    protected JsonNode defeatBoss(String token, String email, int stages) throws Exception {
        JsonNode last = null;
        for (int stage = 0; stage < stages; stage++) {
            last = submitBossStage(token, email, correctBossAnswer(token, email));
        }
        return last;
    }

    /** Rewinds a live boss puzzle's window so the expiry path can be exercised. */
    protected void expireBossPuzzleWindow(String email) {
        UUID userId = userIdOf(email);
        BossEncounter encounter = bossEncounterRepository
                .findByUserIdAndStatus(userId, EncounterStatus.ACTIVE)
                .orElseThrow(() -> new AssertionError("no active boss encounter"));
        PuzzleAttempt puzzle = puzzleRepository
                .findByBossEncounterIdAndAttemptNumber(encounter.getId(), encounter.getCurrentStage())
                .orElseThrow(() -> new AssertionError("no live puzzle for the current stage"));
        java.time.Instant started = java.time.Instant.now().minus(java.time.Duration.ofMinutes(30));
        assertThat(puzzleRepository.rewindow(puzzle.getPuzzleId(), started, started.plusSeconds(60)))
                .as("the boss puzzle should have been re-windowed")
                .isEqualTo(1);
    }

    /**
     * Moves an encounter's whole window into the past so it lapses.
     *
     * <p>Both timestamps are rewound together: the table requires
     * {@code expires_at > started_at}, which is a real invariant and a useful one.
     * Pushing only the expiry into the past would produce a window that never
     * existed.
     */
    protected void expireEncounterWindow(String email) {
        UUID userId = userIdOf(email);
        BossEncounter encounter = bossEncounterRepository
                .findByUserIdAndStatus(userId, EncounterStatus.ACTIVE)
                .orElseThrow(() -> new AssertionError("no active boss encounter"));
        try {
            java.time.Instant started = java.time.Instant.now().minus(java.time.Duration.ofHours(2));
            java.lang.reflect.Field startedField = BossEncounter.class.getDeclaredField("startedAt");
            startedField.setAccessible(true);
            startedField.set(encounter, started);
            java.lang.reflect.Field expiresField = BossEncounter.class.getDeclaredField("expiresAt");
            expiresField.setAccessible(true);
            expiresField.set(encounter, started.plusSeconds(60));
            bossEncounterRepository.saveAndFlush(encounter);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Unable to expire an encounter in a test", ex);
        }
    }

    /** Request body for {@code /player/boss/encounter/stage/submit}. */
    public record BossPayload(java.util.UUID puzzleId, String answer) {
    }

    /** Request body for {@code /auth/register}. */
    public record RegistrationPayload(String username, String email, String password) {
    }

    /** Request body for {@code /auth/login}. */
    public record LoginPayload(String email, String password) {
    }

    /** Request body for {@code /auth/refresh} and {@code /auth/logout}. */
    public record RefreshPayload(String refreshToken) {
    }

    /**
     * Sets a player's coin balance directly.
     *
     * <p>Mirrors {@link #setEnergy}: the entity deliberately exposes no coin
     * setter because production code must go through the reward and purchase
     * paths, but a test needs an exact balance to set up an affordability case
     * and cannot reach one by playing for hours.
     */
    protected void setCoins(String email, long coins) {
        PlayerProfile profile = profileOf(email);
        try {
            java.lang.reflect.Field field = PlayerProfile.class.getDeclaredField("coins");
            field.setAccessible(true);
            field.set(profile, coins);
            profileRepository.saveAndFlush(profile);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Unable to set the coin balance in a test", ex);
        }
        assertThat(profileOf(email).getCoins()).isEqualTo(coins);
    }

    /** Stable id of a seeded item, addressed by its catalogue code. */
    protected UUID itemId(String code) {
        return itemRepository.findByCode(code)
                .map(Item::getId)
                .orElseThrow(() -> new IllegalStateException("Seeded item not found: " + code));
    }

    /** The catalogue row for a seeded item code. */
    protected Item item(String code) {
        return itemRepository.findByCode(code)
                .orElseThrow(() -> new IllegalStateException("Seeded item not found: " + code));
    }

    /**
     * Retires a catalogue item, then restores it.
     *
     * <p>{@link Item} exposes no mutators because in production the catalogue is
     * written by migration alone. A test needs a retired item to prove the shop
     * hides it and refuses to sell it, so the flag is written reflectively -
     * the same approach {@link #setEnergy} uses rather than opening a hole in
     * the entity for production code to use.
     *
     * <p>Restoring in a finally block matters more than it looks: every
     * integration test class shares one in-memory database, so an item left
     * retired by one test would silently break every later test that happens to
     * use it.
     */
    protected void withItemRetired(String code, Runnable assertions) {
        setItemActive(code, false);
        try {
            assertions.run();
        } finally {
            setItemActive(code, true);
        }
    }

    private void setItemActive(String code, boolean active) {
        Item item = item(code);
        try {
            java.lang.reflect.Field field = Item.class.getDeclaredField("active");
            field.setAccessible(true);
            field.set(item, active);
            itemRepository.saveAndFlush(item);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Unable to change an item's active flag in a test", ex);
        }
        assertThat(item(code).isActive()).as("item %s active flag", code).isEqualTo(active);
    }

    /** The caller's inventory row for an item code, or null when unowned. */
    protected java.util.Optional<PlayerInventoryItem> ownedItem(String email, String itemCode) {
        return inventoryRepository.findByUserIdAndItemId(userIdOf(email), itemId(itemCode));
    }

    /** Buys an item through the real endpoint and returns the parsed body. */
    protected JsonNode purchase(String token, UUID itemId) throws Exception {
        MvcResult result = mockMvc.perform(
                        authPost("/api/v1/player/shop/items/" + itemId + "/purchase", token))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    /**
     * Buys an item and returns the raw response without asserting the status,
     * so a test can drive a failure case such as insufficient coins.
     */
    protected MvcResult purchaseExpectingFailure(String token, UUID itemId) throws Exception {
        return mockMvc.perform(authPost("/api/v1/player/shop/items/" + itemId + "/purchase", token))
                .andReturn();
    }

    /** Equips an owned inventory row into a slot through the real endpoint. */
    protected JsonNode equip(String token, EquipmentSlot slot, UUID inventoryItemId) throws Exception {
        MvcResult result = mockMvc.perform(authPost("/api/v1/player/equipment/" + slot, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new EquipPayload(inventoryItemId))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    /** Parsed body of an authenticated GET. */
    protected JsonNode getData(String token, String url) throws Exception {
        MvcResult result = mockMvc.perform(authGet(url, token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    /** Request body for {@code /player/equipment/{slot}}. */
    public record EquipPayload(java.util.UUID inventoryItemId) {
    }

    /** Request body for {@code /missions/{id}/puzzle/submit}. */
    public record PuzzlePayload(java.util.UUID puzzleId, String answer) {
    }
}