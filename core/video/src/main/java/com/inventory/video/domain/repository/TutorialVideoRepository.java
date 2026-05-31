package com.inventory.video.domain.repository;

import com.inventory.video.domain.model.TutorialVideo;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TutorialVideoRepository extends MongoRepository<TutorialVideo, String> {

  Optional<TutorialVideo> findByVideoKeyAndActiveTrue(String videoKey);

  List<TutorialVideo> findByActiveTrueOrderBySortOrderAscTitleAsc();

  boolean existsByVideoKey(String videoKey);
}
