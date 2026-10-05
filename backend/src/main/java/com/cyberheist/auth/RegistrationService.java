package com.cyberheist.auth;

import com.cyberheist.auth.dto.RegisterRequest;
import com.cyberheist.auth.dto.UserResponse;
import com.cyberheist.exception.ConflictException;
import com.cyberheist.player.PlayerProfileService;
import com.cyberheist.user.Role;
import com.cyberheist.user.User;
import com.cyberheist.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

/**
 * Account creation.
 *
 * <p>Kept separate from {@link AuthService} so that adding more signup rules
 * later does not bloat the login flow.
 */
@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

    private final UserRepository userRepository;
    private final PlayerProfileService playerProfileService;
    private final PasswordEncoder passwordEncoder;

    public RegistrationService(UserRepository userRepository,
                               PlayerProfileService playerProfileService,
                               PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.playerProfileService = playerProfileService;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Creates an enabled PLAYER with a starting profile.
     *
     * <p>The account and its profile are written in one transaction, so a
     * player can never exist without game state.
     *
     * @return the safe representation of the new account
     * @throws ConflictException if the username or email is already registered
     */
    @Transactional
    public UserResponse register(RegisterRequest request) {
        String username = request.username().trim();
        String email = request.email().trim().toLowerCase(Locale.ROOT);

        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw new ConflictException("Username is already taken");
        }
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("Email is already registered");
        }

        User user = new User(
                UUID.randomUUID(),
                username,
                email,
                passwordEncoder.encode(request.password()),
                // Assigned server side only. Clients can never request a role.
                Role.PLAYER
        );

        User saved = userRepository.save(user);
        playerProfileService.createInitialProfile(saved.getId(), username);

        log.info("Registered new player '{}' ({})", username, saved.getId());
        return UserMapper.toResponse(saved);
    }
}