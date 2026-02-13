package com.settlement.domain.repository;

import com.settlement.domain.entity.SyncOutbox;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncOutboxRepository extends JpaRepository<SyncOutbox, Long> {
}
