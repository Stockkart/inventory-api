package com.inventory.product.service.creditnote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inventory.documentservice.rest.dto.GenerateCreditNoteRequest;
import com.inventory.product.domain.model.VendorPurchaseReturn;
import com.inventory.product.domain.model.VendorPurchaseReturnItem;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.domain.repository.VendorPurchaseInvoiceRepository;
import com.inventory.product.domain.repository.VendorPurchaseReturnRepository;
import com.inventory.user.domain.repository.VendorRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** A note line with no stock lot behind it printed as "Item" although it carried its name. */
class VendorReturnCreditNoteAssemblerTest {

  @Test
  void aLineWithoutALotPrintsTheNameItWasRecordedWith() {
    VendorPurchaseReturnRepository returns = mock(VendorPurchaseReturnRepository.class);
    InventoryRepository inventory = mock(InventoryRepository.class);
    VendorPurchaseReturnItem line = new VendorPurchaseReturnItem();
    line.setName("LITTLE'S BABY WIPES 72'S 1X24");
    line.setDisplayQuantityReturned(BigDecimal.valueOf(3));
    line.setTaxableValue(new BigDecimal("110.04"));
    VendorPurchaseReturn record = new VendorPurchaseReturn();
    record.setId("ret-1");
    record.setShopId("shop-1");
    record.setItems(List.of(line));
    when(returns.findById("ret-1")).thenReturn(Optional.of(record));
    when(inventory.findAllById(any())).thenReturn(List.of());

    VendorReturnCreditNoteAssembler assembler =
        new VendorReturnCreditNoteAssembler(
            returns,
            mock(VendorPurchaseInvoiceRepository.class),
            mock(VendorRepository.class),
            inventory,
            mock(CreditNoteRequestSupport.class));

    GenerateCreditNoteRequest request = assembler.assemble("ret-1", "shop-1", null, null);

    assertEquals("LITTLE'S BABY WIPES 72'S 1X24", request.getItems().get(0).getName());
  }
}
