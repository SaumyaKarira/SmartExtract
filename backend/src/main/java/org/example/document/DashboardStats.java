package org.example.document;

public record DashboardStats(
        long total,
        long completed,
        long processing,
        long failed
) {}

