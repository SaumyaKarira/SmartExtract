package org.example.auth;

import org.example.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AuthService — professional error messages.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtService jwtService;

    @InjectMocks AuthService authService;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(1L);
        testUser.setName("Test User");
        testUser.setEmail("test@example.com");
        testUser.setPasswordHash("$2a$10$hashedpassword");
    }

    // -------------------------------------------------------------------------
    // Login
    // -------------------------------------------------------------------------

    @Test
    void login_withUnknownEmail_returnsFriendlyMessage() {
        when(userRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("unknown@example.com", "pass")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Incorrect email or password.");
    }

    @Test
    void login_withWrongPassword_returnsFriendlyMessage() {
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("wrongpass", testUser.getPasswordHash())).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("test@example.com", "wrongpass")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Incorrect email or password.");
    }

    @Test
    void login_withCorrectCredentials_returnsToken() {
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("correctpass", testUser.getPasswordHash())).thenReturn(true);
        when(jwtService.generateToken(1L)).thenReturn("jwt-token");

        LoginResponse response = authService.login(new LoginRequest("test@example.com", "correctpass"));

        assertThat(response.token()).isEqualTo("jwt-token");
    }

    @Test
    void login_doesNotExposeInternalDetails() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());

        ResponseStatusException ex = catchThrowableOfType(
                () -> authService.login(new LoginRequest("x@x.com", "pass")),
                ResponseStatusException.class);

        assertThat(ex.getReason()).doesNotContain("not found");
        assertThat(ex.getReason()).doesNotContain("user");
        assertThat(ex.getReason()).doesNotContain("database");
    }

    // -------------------------------------------------------------------------
    // Register
    // -------------------------------------------------------------------------

    @Test
    void register_withDuplicateEmail_returnsFriendlyMessage() {
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));

        assertThatThrownBy(() -> authService.register(new RegisterRequest("Name", "test@example.com", "pass123")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("An account with this email already exists");
    }

    @Test
    void register_duplicateMessage_suggestsSignIn() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(testUser));

        ResponseStatusException ex = catchThrowableOfType(
                () -> authService.register(new RegisterRequest("Name", "test@example.com", "pass123")),
                ResponseStatusException.class);

        assertThat(ex.getReason()).containsIgnoringCase("sign in");
    }
}

