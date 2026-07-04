package com.inventory.user.domain.repository;

import com.inventory.user.domain.model.ShopRbacPolicyDocument;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ShopRbacPolicyRepository extends MongoRepository<ShopRbacPolicyDocument, String> {

  Optional<ShopRbacPolicyDocument> findByShopId(String shopId);
}
