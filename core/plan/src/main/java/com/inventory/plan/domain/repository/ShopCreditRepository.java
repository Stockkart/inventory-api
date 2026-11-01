package com.inventory.plan.domain.repository;

import com.inventory.plan.domain.model.ShopCredit;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ShopCreditRepository extends MongoRepository<ShopCredit, String> {}
