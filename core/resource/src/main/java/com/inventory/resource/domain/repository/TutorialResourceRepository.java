package com.inventory.resource.domain.repository;

import com.inventory.resource.domain.model.TutorialResource;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TutorialResourceRepository extends MongoRepository<TutorialResource, String> {

  Optional<TutorialResource> findByVideoKeyAndActiveTrue(String videoKey);

  List<TutorialResource> findByActiveTrueOrderBySortOrderAscTitleAsc();

  boolean existsByVideoKey(String videoKey);
}
