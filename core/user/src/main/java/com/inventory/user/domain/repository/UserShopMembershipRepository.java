package com.inventory.user.domain.repository;

import com.inventory.user.domain.model.UserShopMembership;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserShopMembershipRepository extends MongoRepository<UserShopMembership, String> {

  List<UserShopMembership> findByUserIdAndActiveTrue(String userId);

  List<UserShopMembership> findByShopIdAndActiveTrue(String shopId);

  Optional<UserShopMembership> findByUserIdAndShopIdAndActiveTrue(String userId, String shopId);

  boolean existsByUserIdAndShopIdAndActiveTrue(String userId, String shopId);
}
