package com.settlement.domain.repository;

import com.settlement.domain.entity.DailySettlement;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DailySettlementRepository extends JpaRepository<DailySettlement, Long> {
}
