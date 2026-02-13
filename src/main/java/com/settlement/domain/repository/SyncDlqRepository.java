package com.settlement.domain.repository;

import com.settlement.domain.entity.SyncDlq;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncDlqRepository extends JpaRepository<SyncDlq, Long> {
}
