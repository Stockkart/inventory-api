package com.inventory.product.domain.repository;

import com.inventory.product.domain.model.ShopInvoiceSettingsDocument;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ShopInvoiceSettingsRepository
    extends MongoRepository<ShopInvoiceSettingsDocument, String> {

  Optional<ShopInvoiceSettingsDocument> findByShopId(String shopId);
}
