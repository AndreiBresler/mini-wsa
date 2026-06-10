package com.akamai.miniwsa.generator;

import com.akamai.miniwsa.domain.Action;
import com.akamai.miniwsa.domain.Category;
import com.akamai.miniwsa.domain.Severity;

import java.util.List;
import java.util.Map;

/**
 * All static pools and weights used by the generator. Constants only; no logic.
 */
public final class GeneratorConstants {

    private GeneratorConstants() {
    }

    public static final List<String> CLIENT_IPS = List.of(
            "203.0.113.10", "203.0.113.11", "203.0.113.12", "203.0.113.13", "203.0.113.14",
            "203.0.113.15", "203.0.113.16", "203.0.113.17", "203.0.113.18", "203.0.113.19",
            "198.51.100.10", "198.51.100.11", "198.51.100.12", "198.51.100.13", "198.51.100.14",
            "198.51.100.15", "198.51.100.16", "198.51.100.17", "198.51.100.18", "198.51.100.19",
            "192.0.2.10", "192.0.2.11", "192.0.2.12", "192.0.2.13", "192.0.2.14",
            "192.0.2.15", "192.0.2.16", "192.0.2.17", "192.0.2.18", "192.0.2.19",
            "10.0.0.5", "10.0.0.6", "10.0.0.7", "10.0.0.8", "10.0.0.9",
            "172.16.0.5", "172.16.0.6", "172.16.0.7", "172.16.0.8", "172.16.0.9",
            "104.21.1.5", "104.21.1.6", "104.21.1.7", "151.101.1.5", "151.101.1.6",
            "52.84.1.5", "52.84.1.6", "13.32.1.5", "13.32.1.6", "8.8.8.8"
    );

    public static final List<String> BAD_ACTOR_IPS = List.of(
            "203.0.113.42", "203.0.113.66", "198.51.100.99", "198.51.100.13", "192.0.2.200",
            "192.0.2.66", "45.142.122.1", "185.220.101.5", "194.165.16.99", "51.81.42.1"
    );

    public static final List<String> PATHS = List.of(
            "/api/v1/login", "/api/v1/users", "/api/v1/orders", "/api/v1/products", "/api/v1/search",
            "/api/v1/checkout", "/api/v1/cart", "/api/v2/users", "/api/v2/auth",
            "/admin", "/admin/users", "/admin/settings", "/admin/logs",
            "/login", "/logout", "/register", "/profile",
            "/search", "/comments", "/comments/submit", "/posts", "/posts/new",
            "/robots.txt", "/sitemap.xml", "/favicon.ico", "/healthz",
            "/static/main.js", "/static/style.css", "/static/logo.png",
            "/uploads/image", "/downloads/report"
    );

    public static final List<String> SENSITIVE_PATHS = List.of(
            "/login", "/api/v1/login", "/admin", "/admin/users", "/api/admin/dump"
    );

    public static final List<String> USER_AGENTS = List.of(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15) AppleWebKit/605.1.15",
            "Mozilla/5.0 (X11; Linux x86_64) Gecko/20100101 Firefox/120.0",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)",
            "Googlebot/2.1 (+http://www.google.com/bot.html)",
            "Bingbot/2.0 (+http://www.bing.com/bingbot.htm)",
            "curl/8.5.0",
            "Python-urllib/3.11",
            "sqlmap/1.7.10",
            "Nikto/2.5.0",
            "Wget/1.21.4",
            "Apache-HttpClient/4.5.14",
            "Go-http-client/1.1",
            "PostmanRuntime/7.36.0",
            "BadBot/1.0"
    );

    public static final List<Geo> GEO_COUNTRIES = List.of(
            new Geo("RU", "Moscow"),
            new Geo("CN", "Beijing"),
            new Geo("US", "Ashburn"),
            new Geo("NL", "Amsterdam"),
            new Geo("BR", "Sao Paulo")
    );

    public static final int[] GEO_WEIGHTS = {30, 25, 20, 15, 10};

    public static final List<Category> CATEGORIES = List.of(
            Category.BOT, Category.INJECTION, Category.XSS, Category.RATE_LIMIT,
            Category.DOS, Category.PROTOCOL_VIOLATION, Category.DATA_LEAKAGE
    );

    public static final int[] CATEGORY_WEIGHTS = {30, 20, 15, 12, 10, 8, 5};

    public static final List<Severity> SEVERITIES = List.of(
            Severity.LOW, Severity.MEDIUM, Severity.HIGH, Severity.CRITICAL
    );

    public static final int[] SEVERITY_WEIGHTS = {40, 30, 20, 10};

    public static final List<String> HTTP_METHODS = List.of("GET", "POST", "PUT", "DELETE");

    public static final Map<Category, String> RULE_ID_BY_CATEGORY = Map.of(
            Category.INJECTION, "950001",
            Category.XSS, "941100",
            Category.PROTOCOL_VIOLATION, "920200",
            Category.DATA_LEAKAGE, "920100",
            Category.BOT, "913100",
            Category.DOS, "920400",
            Category.RATE_LIMIT, "920500"
    );

    public static final Map<Category, String> RULE_NAME_BY_CATEGORY = Map.of(
            Category.INJECTION, "SQL_INJECTION",
            Category.XSS, "XSS_REFLECTED",
            Category.PROTOCOL_VIOLATION, "PROTOCOL_ANOMALY",
            Category.DATA_LEAKAGE, "DATA_EXFIL",
            Category.BOT, "BOT_SCRAPER",
            Category.DOS, "DOS_SLOWLORIS",
            Category.RATE_LIMIT, "RATE_LIMIT_EXCEEDED"
    );

    /**
     * Action probability table indexed by severity, in order DENY/ALERT/MONITOR.
     * Rows must sum to 100.
     */
    public static final Map<Severity, int[]> ACTION_WEIGHTS_BY_SEVERITY = Map.of(
            Severity.CRITICAL, new int[]{80, 20, 0},
            Severity.HIGH,     new int[]{60, 40, 0},
            Severity.MEDIUM,   new int[]{0,  70, 30},
            Severity.LOW,      new int[]{0,  20, 80}
    );

    public static final List<Action> ACTIONS_ORDER = List.of(Action.DENY, Action.ALERT, Action.MONITOR);

    public record Geo(String country, String city) {
    }
}
