package com.inventory.plan.domain.repository;

import com.inventory.plan.domain.model.ShopAddOn;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ShopAddOnRepository extends MongoRepository<ShopAddOn, String> {

  List<ShopAddOn> findByShopId(String shopId);

  List<ShopAddOn> findBySourceOrderId(String sourceOrderId);
}
