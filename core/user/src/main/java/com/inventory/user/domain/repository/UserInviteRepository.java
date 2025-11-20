package com.inventory.user.domain.repository;

import com.inventory.user.domain.model.UserInvite;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserInviteRepository extends MongoRepository<UserInvite, String> {

    Optional<UserInvite> findByToken(String token);
}

