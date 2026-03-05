package com.inventory.plan.domain.repository;

import com.inventory.plan.domain.model.Plan;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PlanRepository extends MongoRepository<Plan, String> {

  List<Plan> findAllByOrderByPriceAsc();

  java.util.Optional<Plan> findByPlanName(String planName);
}
