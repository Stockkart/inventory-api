package com.inventory.plan.domain.repository;

import com.inventory.plan.domain.model.AddOnVoucher;
import com.inventory.plan.domain.model.VoucherTarget;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AddOnVoucherRepository extends MongoRepository<AddOnVoucher, String> {

  Optional<AddOnVoucher> findByCode(String code);

  List<AddOnVoucher> findByPlanCode(String planCode, Pageable pageable);

  List<AddOnVoucher> findByTarget(VoucherTarget target, Pageable pageable);

  /** Mongo $ne matches missing fields too, so this returns ADD_ON and legacy null-target vouchers. */
  List<AddOnVoucher> findByTargetNot(VoucherTarget target, Pageable pageable);

  List<AddOnVoucher> findByAddOnCode(String addOnCode, Pageable pageable);
}
