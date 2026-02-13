package com.settlement.domain.repository;

import com.settlement.domain.entity.Merchant;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MerchantRepository extends JpaRepository<Merchant, Long> {
}
