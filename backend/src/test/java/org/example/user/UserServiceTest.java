package org.example.user;

import org.example.auth.AuthService;
import org.example.auth.JwtService;
import org.example.auth.LoginRequest;
import org.example.auth.LoginResponse;
import org.example.auth.UserRepository;
import org.example.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the name-update flow:
 *   updateProfile → persisted in DB → login returns the updated name.
 *
 * Covers the bug where login reused stale cached names instead of the
 * authoritative value from the database.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtService jwtService;

    @InjectMocks UserService userService;
    @InjectMocks AuthService authService;

    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(1L);
        user.setName("Original Name");
        user.setEmail("user@example.com");
        user.setPasswordHash("$2a$10$hashedpassword");
    }

    // ── updateProfile ─────────────────────────────────────────────────────────

    @Test
    void updateProfile_persistsNewNameInDatabase() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserProfileResponse response = userService.updateProfile(1L, new UpdateProfileRequest("New Name"));

        // Verify the entity was mutated and saved
        assertThat(user.getName()).isEqualTo("New Name");
        verify(userRepository).save(user);
        // Response immediately reflects the new name
        assertThat(response.name()).isEqualTo("New Name");
        assertThat(response.email()).isEqualTo("user@example.com");
    }

    @Test
    void updateProfile_stripsLeadingAndTrailingWhitespace() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserProfileResponse response = userService.updateProfile(1L, new UpdateProfileRequest("  Trimmed Name  "));

        assertThat(user.getName()).isEqualTo("Trimmed Name");
        assertThat(response.name()).isEqualTo("Trimmed Name");
    }

    @Test
    void updateProfile_updatesCorrectUser() {
        // Only the authenticated user's record should be updated
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        userService.updateProfile(1L, new UpdateProfileRequest("Updated"));

        verify(userRepository).findById(1L);
        verify(userRepository, never()).findById(argThat(id -> !id.equals(1L)));
    }

    // ── Validation ────────────────────────────────────────────────────────────

    @Test
    void updateProfile_emptyName_throwsBadRequest() {
        assertThatThrownBy(() -> userService.updateProfile(1L, new UpdateProfileRequest("")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        verify(userRepository, never()).save(any());
    }

    @Test
    void updateProfile_blankName_throwsBadRequest() {
        assertThatThrownBy(() -> userService.updateProfile(1L, new UpdateProfileRequest("   ")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void updateProfile_nullName_throwsBadRequest() {
        assertThatThrownBy(() -> userService.updateProfile(1L, new UpdateProfileRequest(null)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void updateProfile_nameTooLong_throwsBadRequest() {
        String tooLong = "A".repeat(101);
        assertThatThrownBy(() -> userService.updateProfile(1L, new UpdateProfileRequest(tooLong)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void updateProfile_exactly100Chars_succeeds() {
        String exactly100 = "A".repeat(100);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThatCode(() -> userService.updateProfile(1L, new UpdateProfileRequest(exactly100)))
                .doesNotThrowAnyException();
    }

    // ── Login returns updated name (the bug fix) ──────────────────────────────

    @Test
    void login_afterNameUpdate_returnsUpdatedNameFromDatabase() {
        // Simulate: user updates their name in the DB
        user.setName("Updated Name");

        // Login reads from DB — must return the updated name, not any cached value
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correctpass", user.getPasswordHash())).thenReturn(true);
        when(jwtService.generateToken(1L)).thenReturn("jwt-token");

        LoginResponse response = authService.login(new LoginRequest("user@example.com", "correctpass"));

        assertThat(response.token()).isEqualTo("jwt-token");
        assertThat(response.name()).isEqualTo("Updated Name"); // authoritative from DB
        assertThat(response.email()).isEqualTo("user@example.com");
    }

    @Test
    void login_returnsNameAndEmailFromDatabase_notCachedValues() {
        // This test documents the contract: login response ALWAYS contains DB values.
        // Previously the frontend used stale localStorage; now it uses the response.
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("pass", user.getPasswordHash())).thenReturn(true);
        when(jwtService.generateToken(1L)).thenReturn("token");

        LoginResponse response = authService.login(new LoginRequest("user@example.com", "pass"));

        assertThat(response.name()).isNotNull().isNotBlank();
        assertThat(response.email()).isEqualTo("user@example.com");
    }

    // ── getProfile ────────────────────────────────────────────────────────────

    @Test
    void getProfile_returnsCurrentNameFromDatabase() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        UserProfileResponse profile = userService.getProfile(1L);

        assertThat(profile.name()).isEqualTo("Original Name");
        assertThat(profile.email()).isEqualTo("user@example.com");
        assertThat(profile.id()).isEqualTo(1L);
    }
}

