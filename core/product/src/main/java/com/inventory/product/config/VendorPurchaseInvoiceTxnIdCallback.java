package com.inventory.product.config;

import com.inventory.common.util.TxnIdGenerator;
import com.inventory.product.domain.model.VendorPurchaseInvoice;
import org.springframework.data.mongodb.core.mapping.event.BeforeConvertCallback;
import org.springframework.stereotype.Component;

/**
 * Stamps a business {@code txnId} on every invoice that lacks one, immediately before Spring Data
 * converts the entity into a Mongo document. Making this a lifecycle callback rather than a service
 * concern means the invariant "a persisted invoice always has a txnId" holds for every repository
 * write path, including ones added later.
 *
 * <p>It never overwrites an existing value — a txnId is part of the API contract once issued.
 */
@Component
public class VendorPurchaseInvoiceTxnIdCallback
    implements BeforeConvertCallback<VendorPurchaseInvoice> {

  @Override
  public VendorPurchaseInvoice onBeforeConvert(VendorPurchaseInvoice entity, String collection) {
    if (entity.getTxnId() == null) {
      entity.setTxnId(TxnIdGenerator.generate());
    }
    return entity;
  }
}
