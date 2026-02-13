package com.settlement.domain.repository;

import com.settlement.domain.entity.SettlementError;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementErrorRepository extends JpaRepository<SettlementError, Long> {
}
