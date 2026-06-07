package com.akamai.miniwsa.domain;

public enum Action {
    DENY(20),
    ALERT(10),
    MONITOR(0);

    private final int points;

    Action(int points) {
        this.points = points;
    }

    public int points() {
        return points;
    }
}
