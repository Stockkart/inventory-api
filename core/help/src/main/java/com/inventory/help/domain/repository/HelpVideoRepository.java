package com.inventory.help.domain.repository;

import com.inventory.help.domain.model.HelpVideo;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface HelpVideoRepository extends MongoRepository<HelpVideo, String> {

  Optional<HelpVideo> findByVideoKeyAndActiveTrue(String videoKey);

  List<HelpVideo> findByActiveTrueOrderBySortOrderAscTitleAsc();

  boolean existsByVideoKey(String videoKey);
}
