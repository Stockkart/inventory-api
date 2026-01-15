package com.inventory.tax.domain.repository;

import com.inventory.tax.domain.model.GstReturn;
import com.inventory.tax.domain.model.GstReturnStatus;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GstReturnRepository extends MongoRepository<GstReturn, String> {
    
    List<GstReturn> findByShopId(String shopId);
    
    List<GstReturn> findByShopIdAndReturnType(String shopId, String returnType);
    
    Optional<GstReturn> findByShopIdAndReturnTypeAndPeriod(String shopId, String returnType, String period);
    
    List<GstReturn> findByShopIdAndStatus(String shopId, GstReturnStatus status);
    
    List<GstReturn> findByShopIdAndPeriod(String shopId, String period);
}

