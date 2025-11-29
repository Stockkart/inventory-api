package com.inventory.product.domain.repository;

import com.inventory.product.domain.model.Purchase;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PurchaseRepository extends MongoRepository<Purchase, String> {

  Optional<Purchase> findByInvoiceNo(String invoiceNo);
}

