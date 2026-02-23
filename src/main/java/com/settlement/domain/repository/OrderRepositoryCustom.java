package com.settlement.domain.repository;

import com.settlement.domain.dto.MerchantSettlementDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;

public interface OrderRepositoryCustom {

    Page<MerchantSettlementDto> findSettlementAggregation(LocalDate settlementDate, Pageable pageable);
}