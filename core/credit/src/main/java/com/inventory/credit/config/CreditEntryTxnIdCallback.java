package com.inventory.credit.config;

import com.inventory.common.util.TxnIdGenerator;
import com.inventory.credit.domain.model.CreditEntry;
import org.springframework.data.mongodb.core.mapping.event.BeforeConvertCallback;
import org.springframework.stereotype.Component;

/**
 * Stamps a business {@code txnId} on every credit entry that lacks one, immediately before Spring
 * Data converts the entity into a Mongo document. Credit entries surface in the vendor money MIS as
 * both payments and credit charges, so they need the same stable identifier as invoices and returns.
 *
 * <p>It never overwrites an existing value.
 */
@Component
public class CreditEntryTxnIdCallback implements BeforeConvertCallback<CreditEntry> {

  @Override
  public CreditEntry onBeforeConvert(CreditEntry entity, String collection) {
    if (entity.getTxnId() == null) {
      entity.setTxnId(TxnIdGenerator.generate());
    }
    return entity;
  }
}
