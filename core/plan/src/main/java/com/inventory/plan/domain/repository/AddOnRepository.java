package com.inventory.plan.domain.repository;

import com.inventory.plan.domain.model.AddOn;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AddOnRepository extends MongoRepository<AddOn, String> {

  Optional<AddOn> findByCode(String code);

  List<AddOn> findByCodeIn(Collection<String> codes);
}
