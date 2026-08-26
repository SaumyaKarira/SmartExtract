package org.example.user;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public UserProfileResponse getProfile(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return userService.getProfile(userId);
    }

    @PatchMapping("/profile")
    public UserProfileResponse updateProfile(
            @RequestBody UpdateProfileRequest request,
            Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return userService.updateProfile(userId, request);
    }

    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(
            @RequestBody ChangePasswordRequest request,
            Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        userService.changePassword(userId, request);
        return ResponseEntity.noContent().build();
    }
}

