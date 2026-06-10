package com.akamai.miniwsa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.time.LocalDateTime;

@SpringBootApplication
public class MiniWsaApplication {

    public static void main(String[] args) {
        LocalDateTime now = LocalDateTime.now();
        String runId = String.format("%04d_%02d_%02d_%02d_%02d_%02d_%09d",
                now.getYear(), now.getMonthValue(), now.getDayOfMonth(),
                now.getHour(), now.getMinute(), now.getSecond(), now.getNano());
        System.setProperty("RUN_ID", runId);

        SpringApplication.run(MiniWsaApplication.class, args);
    }
}