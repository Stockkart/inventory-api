package com.inventory.product.domain.repository;

import com.inventory.product.domain.model.Sale;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SaleRepository extends MongoRepository<Sale, String> {

    Optional<Sale> findByInvoiceNo(String invoiceNo);
}

