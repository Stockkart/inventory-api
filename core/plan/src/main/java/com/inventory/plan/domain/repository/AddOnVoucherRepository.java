package com.inventory.plan.domain.repository;

import com.inventory.plan.domain.model.AddOnVoucher;
import com.inventory.plan.domain.model.VoucherTarget;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface AddOnVoucherRepository extends MongoRepository<AddOnVoucher, String> {

  Optional<AddOnVoucher> findByCode(String code);

  List<AddOnVoucher> findByCodeIn(Collection<String> codes);

  List<AddOnVoucher> findByPlanCode(String planCode, Pageable pageable);

  List<AddOnVoucher> findByTarget(VoucherTarget target, Pageable pageable);

  /**
   * Add-on vouchers, legacy rows without a target included ({@code $in} with null matches a missing
   * field), as an index-friendly equality on {@code target_createdAt}. {@code VoucherTargetBackfill}
   * sets the missing targets.
   */
  @Query("{ 'target': { $in: [ 'ADD_ON', null ] } }")
  List<AddOnVoucher> findAddOnTargeted(Pageable pageable);

  List<AddOnVoucher> findByAddOnCode(String addOnCode, Pageable pageable);
}
