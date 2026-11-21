package com.inventory.hr.domain.repository;

import com.inventory.hr.domain.model.HrRolePermissions;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface HrRolePermissionsRepository extends MongoRepository<HrRolePermissions, String> {

  Optional<HrRolePermissions> findByShopId(String shopId);
}
