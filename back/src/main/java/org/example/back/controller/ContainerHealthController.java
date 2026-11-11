package org.example.back.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
@ConditionalOnProperty(name = "app.container-bootstrap", havingValue = "true")
public class ContainerHealthController {
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    public ContainerHealthController(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }
    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, String>> ready() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            try (var connection = redis.getConnectionFactory().getConnection()) {
                if (!"PONG".equals(connection.ping())) throw new IllegalStateException();
            }
            return ResponseEntity.ok(Map.of("status", "UP"));
        } catch (Exception ignored) {
            return ResponseEntity.status(503).body(Map.of("status", "DOWN"));
        }
    }
}
