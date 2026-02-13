package com.settlement;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class DatabaseCleanup {

    private static final List<String> TABLES = List.of(
            "sync_dlq",
            "sync_outbox",
            "settlement_error",
            "daily_settlement",
            "settlement_staging",
            "payments",
            "orders",
            "merchants"
    );

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public void execute() {
        entityManager.flush();
        for (String table : TABLES) {
            entityManager.createNativeQuery("TRUNCATE TABLE " + table + " CASCADE")
                    .executeUpdate();
        }
    }
}
