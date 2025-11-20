package com.inventory.user.domain.repository;

import com.inventory.user.domain.model.UserAccount;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserAccountRepository extends MongoRepository<UserAccount, String> {

    Optional<UserAccount> findByEmail(String email);

    List<UserAccount> findByShopId(String shopId);
}

