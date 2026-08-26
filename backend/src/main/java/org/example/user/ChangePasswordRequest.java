package org.example.user;

public record ChangePasswordRequest(String currentPassword, String newPassword) {}

