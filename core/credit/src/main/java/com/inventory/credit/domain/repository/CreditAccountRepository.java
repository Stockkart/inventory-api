package com.inventory.credit.domain.repository;

import com.inventory.credit.domain.model.CreditAccount;
import com.inventory.credit.domain.model.CreditPartyType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CreditAccountRepository extends MongoRepository<CreditAccount, String> {

  Optional<CreditAccount> findByShopIdAndPartyTypeAndPartyRefId(
      String shopId, CreditPartyType partyType, String partyRefId);

  List<CreditAccount> findByShopIdAndPartyType(String shopId, CreditPartyType partyType);

  List<CreditAccount> findByShopId(String shopId);
}
