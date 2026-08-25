package org.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseConnectivityVerifier implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConnectivityVerifier.class);

    private final JdbcTemplate jdbcTemplate;

    public DatabaseConnectivityVerifier(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        String dbVersion = jdbcTemplate.queryForObject("SELECT version()", String.class);
        log.info("✅ PostgreSQL connected successfully. Version: {}", dbVersion);

        Integer tableCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE'",
            Integer.class
        );
        log.info("✅ Flyway migrations applied. Tables in public schema: {}", tableCount);
    }
}

