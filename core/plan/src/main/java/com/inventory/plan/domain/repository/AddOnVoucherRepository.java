package com.inventory.plan.domain.repository;

import com.inventory.plan.domain.model.AddOnVoucher;
import com.inventory.plan.domain.model.VoucherTarget;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AddOnVoucherRepository extends MongoRepository<AddOnVoucher, String> {

  Optional<AddOnVoucher> findByCode(String code);

  List<AddOnVoucher> findByAddOnCode(String addOnCode, Sort sort);

  List<AddOnVoucher> findByPlanCode(String planCode, Sort sort);

  List<AddOnVoucher> findByTarget(VoucherTarget target, Sort sort);
}
