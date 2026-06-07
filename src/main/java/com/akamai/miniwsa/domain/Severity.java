package com.akamai.miniwsa.domain;

public enum Severity {
    CRITICAL(40),
    HIGH(30),
    MEDIUM(20),
    LOW(10);

    private final int points;

    Severity(int points) {
        this.points = points;
    }

    public int points() {
        return points;
    }
}
