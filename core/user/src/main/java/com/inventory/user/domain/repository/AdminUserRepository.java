package com.inventory.user.domain.repository;

import com.inventory.user.domain.model.AdminUser;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AdminUserRepository extends MongoRepository<AdminUser, String> {

  Optional<AdminUser> findByEmail(String email);

  boolean existsByEmail(String email);

  long countByActiveTrue();

  List<AdminUser> findAllByOrderByCreatedAtDesc();
}
