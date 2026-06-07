package com.akamai.miniwsa.enrichment;

import com.akamai.miniwsa.domain.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ThreatScoreCalculatorTest {

    @Test
    void critical_deny_on_admin_path_with_repeat_offender_caps_at_100() {
        SecurityEvent e = event(Severity.CRITICAL, Action.DENY, "/admin/users");
        int score = ThreatScoreCalculator.calculate(e, true);
        assertThat(score).isEqualTo(90).isLessThanOrEqualTo(100);
    }

    @Test
    void critical_deny_login_path_repeat_caps_at_100() {
        SecurityEvent e = event(Severity.CRITICAL, Action.DENY, "/api/v1/login");
        int score = ThreatScoreCalculator.calculate(e, true);
        assertThat(score).isEqualTo(90);
    }

    @Test
    void low_monitor_unprivileged_path_is_minimum() {
        SecurityEvent e = event(Severity.LOW, Action.MONITOR, "/static/index.html");
        int score = ThreatScoreCalculator.calculate(e, false);
        assertThat(score).isEqualTo(10);
    }

    @Test
    void medium_alert_no_bonuses() {
        SecurityEvent e = event(Severity.MEDIUM, Action.ALERT, "/api/products");
        int score = ThreatScoreCalculator.calculate(e, false);
        assertThat(score).isEqualTo(30);
    }

    @Test
    void high_deny_with_login_path_only() {
        SecurityEvent e = event(Severity.HIGH, Action.DENY, "/auth/login/submit");
        int score = ThreatScoreCalculator.calculate(e, false);
        assertThat(score).isEqualTo(65);
    }

    @Test
    void null_path_does_not_trigger_sensitive_bonus() {
        SecurityEvent e = event(Severity.HIGH, Action.DENY, null);
        int score = ThreatScoreCalculator.calculate(e, false);
        assertThat(score).isEqualTo(50);
    }

    @Test
    void score_caps_at_100_when_sum_exceeds() {
        SecurityEvent e = event(Severity.CRITICAL, Action.DENY, "/admin/login");
        int score = ThreatScoreCalculator.calculate(e, true);
        assertThat(score).isEqualTo(90);
    }

    @Test
    void attack_type_mapping_critical_injection() {
        assertThat(Category.INJECTION.attackType()).isEqualTo("SQL/Command Injection");
        assertThat(Category.XSS.attackType()).isEqualTo("Cross-Site Scripting");
        assertThat(Category.PROTOCOL_VIOLATION.attackType()).isEqualTo("Protocol Anomaly");
        assertThat(Category.DATA_LEAKAGE.attackType()).isEqualTo("Data Exfiltration");
        assertThat(Category.BOT.attackType()).isEqualTo("Bot Activity");
        assertThat(Category.DOS.attackType()).isEqualTo("Denial of Service");
        assertThat(Category.RATE_LIMIT.attackType()).isEqualTo("Rate Limiting");
    }

    private static SecurityEvent event(Severity severity, Action action, String path) {
        Rule rule = new Rule("950001", "TEST", "msg", severity, Category.INJECTION);
        return new SecurityEvent(
                "evt-1", Instant.parse("2026-05-20T14:32:10Z"), 14227, "pol_web1",
                "203.0.113.42", "www.example.com", path, "POST", 403, "ua",
                rule, action, new GeoLocation("CN", "Beijing"), 1024L, 256L
        );
    }
}
