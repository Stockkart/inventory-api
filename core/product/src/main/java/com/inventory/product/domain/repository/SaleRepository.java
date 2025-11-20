package com.inventory.product.domain.repository;

import com.inventory.product.domain.model.Sale;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface SaleRepository extends MongoRepository<Sale, String> {

    Optional<Sale> findByInvoiceNo(String invoiceNo);
}

