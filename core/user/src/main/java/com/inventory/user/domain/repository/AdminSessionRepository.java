package com.inventory.user.domain.repository;

import com.inventory.user.domain.model.AdminSession;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AdminSessionRepository extends MongoRepository<AdminSession, String> {

  Optional<AdminSession> findByTokenHash(String tokenHash);

  void deleteByTokenHash(String tokenHash);

  void deleteByAdminId(String adminId);

  void deleteByAdminIdAndIdNot(String adminId, String keepSessionId);
}
