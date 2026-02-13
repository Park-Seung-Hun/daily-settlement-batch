package com.settlement.domain.repository;

import com.settlement.domain.entity.Merchant;

import io.zonky.test.db.AutoConfigureEmbeddedDatabase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class MerchantRepositoryTest {

    @Autowired
    private MerchantRepository merchantRepository;

    @Test
    @DisplayName("시나리오 1: Merchant를 저장하면 ID가 생성된다")
    void should_generate_id_when_merchant_saved() {
        Merchant merchant = Merchant.builder()
                .merchantName("테스트 가맹점")
                .businessNumber("1234567890")
                .feeRate(new BigDecimal("0.0300"))
                .build();

        Merchant saved = merchantRepository.save(merchant);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getMerchantName()).isEqualTo("테스트 가맹점");
    }

    @Test
    @DisplayName("시나리오 2: 저장된 Merchant를 ID로 조회할 수 있다")
    void should_find_merchant_when_searched_by_id() {
        Merchant merchant = merchantRepository.save(Merchant.builder()
                .merchantName("조회 테스트")
                .businessNumber("9876543210")
                .feeRate(new BigDecimal("0.0250"))
                .build());

        Optional<Merchant> found = merchantRepository.findById(merchant.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getBusinessNumber()).isEqualTo("9876543210");
    }
}
