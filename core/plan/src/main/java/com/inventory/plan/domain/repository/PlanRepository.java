package com.inventory.plan.domain.repository;

import com.inventory.plan.domain.model.Plan;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlanRepository extends MongoRepository<Plan, String> {

  Optional<Plan> findByPlanName(String planName);

  Optional<Plan> findByCode(String code);

  List<Plan> findByCodeIsNull();
}
