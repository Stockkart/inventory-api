package com.inventory.product.domain.repository;

import com.inventory.product.domain.model.UploadToken;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface UploadTokenRepository extends MongoRepository<UploadToken, String> {
  
  Optional<UploadToken> findByToken(String token);
  
  void deleteByExpiresAtBefore(Instant now);
  
  Optional<UploadToken> findByTokenAndStatus(String token, UploadToken.UploadStatus status);
}
