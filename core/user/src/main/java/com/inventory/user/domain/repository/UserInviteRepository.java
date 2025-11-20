package com.inventory.user.domain.repository;

import com.inventory.user.domain.model.UserInvite;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface UserInviteRepository extends MongoRepository<UserInvite, String> {

    Optional<UserInvite> findByToken(String token);
}

