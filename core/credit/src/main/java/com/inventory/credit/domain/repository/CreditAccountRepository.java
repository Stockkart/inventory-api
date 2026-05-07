package com.inventory.credit.domain.repository;

import com.inventory.credit.domain.model.CreditAccount;
import com.inventory.credit.domain.model.CreditPartyType;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CreditAccountRepository extends MongoRepository<CreditAccount, String> {

  Optional<CreditAccount> findByShopIdAndPartyTypeAndPartyRefId(
      String shopId, CreditPartyType partyType, String partyRefId);
}
