package com.inventory.accounting.domain.repository;

import com.inventory.accounting.domain.model.Account;
import com.inventory.accounting.domain.model.AccountType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AccountRepository extends MongoRepository<Account, String> {

  Optional<Account> findByShopIdAndCode(String shopId, String code);

  List<Account> findByShopIdOrderByCodeAsc(String shopId);

  List<Account> findByShopIdAndTypeOrderByCodeAsc(String shopId, AccountType type);

  boolean existsByShopIdAndCode(String shopId, String code);
}
