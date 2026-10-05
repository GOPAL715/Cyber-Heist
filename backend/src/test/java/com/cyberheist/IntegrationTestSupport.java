package com.cyberheist;

import com.cyberheist.mission.Mission;
import com.cyberheist.mission.MissionProgressRepository;
import com.cyberheist.mission.MissionRepository;
import com.cyberheist.player.PlayerProfile;
import com.cyberheist.player.PlayerProfileRepository;
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

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
    protected MissionProgressRepository progressRepository;

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

    /** Request body for {@code /auth/register}. */
    public record RegistrationPayload(String username, String email, String password) {
    }

    /** Request body for {@code /auth/login}. */
    public record LoginPayload(String email, String password) {
    }

    /** Request body for {@code /auth/refresh} and {@code /auth/logout}. */
    public record RefreshPayload(String refreshToken) {
    }
}