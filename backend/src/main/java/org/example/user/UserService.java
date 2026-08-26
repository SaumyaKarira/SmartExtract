package org.example.user;

import org.example.auth.UserRepository;
import org.example.entity.User;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public UserProfileResponse getProfile(Long userId) {
        User user = findUser(userId);
        return new UserProfileResponse(user.getId(), user.getName(), user.getEmail());
    }

    @Transactional
    public UserProfileResponse updateProfile(Long userId, UpdateProfileRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name cannot be empty.");
        }
        if (request.name().length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name must be 100 characters or fewer.");
        }
        User user = findUser(userId);
        user.setName(request.name().strip());
        userRepository.save(user);
        return new UserProfileResponse(user.getId(), user.getName(), user.getEmail());
    }

    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        if (request.newPassword() == null || request.newPassword().length() < 6) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "New password must be at least 6 characters.");
        }
        User user = findUser(userId);
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Current password is incorrect.");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found."));
    }
}

