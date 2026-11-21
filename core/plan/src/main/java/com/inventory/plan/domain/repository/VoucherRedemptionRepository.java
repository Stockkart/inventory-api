package com.inventory.plan.domain.repository;

import com.inventory.plan.domain.model.VoucherRedemption;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface VoucherRedemptionRepository extends MongoRepository<VoucherRedemption, String> {

  List<VoucherRedemption> findByOrderId(String orderId);

  Optional<VoucherRedemption> findByVoucherIdAndOrderId(String voucherId, String orderId);

  /** Matches the {@code single_use_per_shop} partial index filter, so the index answers it. */
  boolean existsByVoucherCodeAndShopIdAndHoldsSlotTrueAndSingleUsePerShopTrue(String voucherCode, String shopId);

  List<VoucherRedemption> findByVoucherId(String voucherId, Sort sort);
}
