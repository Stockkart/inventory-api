package com.inventory.product.config;

import com.inventory.common.util.TxnIdGenerator;
import com.inventory.product.domain.model.VendorPurchaseReturn;
import org.springframework.data.mongodb.core.mapping.event.BeforeConvertCallback;
import org.springframework.stereotype.Component;

/**
 * Stamps a business {@code txnId} on every purchase return that lacks one. See
 * {@link VendorPurchaseInvoiceTxnIdCallback} for why this is a lifecycle callback.
 */
@Component
public class VendorPurchaseReturnTxnIdCallback
    implements BeforeConvertCallback<VendorPurchaseReturn> {

  @Override
  public VendorPurchaseReturn onBeforeConvert(VendorPurchaseReturn entity, String collection) {
    if (entity.getTxnId() == null) {
      entity.setTxnId(TxnIdGenerator.generate());
    }
    return entity;
  }
}
