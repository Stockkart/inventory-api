package com.inventory.user.domain.repository;

import com.inventory.user.domain.model.UserPhoto;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserPhotoRepository extends MongoRepository<UserPhoto, String> {}
