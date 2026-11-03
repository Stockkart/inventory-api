package com.inventory.hr.domain.repository;

import com.inventory.hr.domain.model.Holiday;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface HolidayRepository extends MongoRepository<Holiday, String> {

  Optional<Holiday> findByShopIdAndId(String shopId, String id);

  List<Holiday> findByShopIdAndYearOrderByDateAsc(String shopId, Integer year);

  boolean existsByShopIdAndDate(String shopId, String date);

  long deleteByShopIdAndId(String shopId, String id);
}
