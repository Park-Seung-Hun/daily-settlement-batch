package com.settlement.domain.repository;

import com.settlement.domain.entity.SettlementStaging;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementStagingRepository extends JpaRepository<SettlementStaging, Long> {
}
