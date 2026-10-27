package com.inventory.product.domain.model.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;

import com.inventory.pricing.rest.dto.request.PricingUpdateCommand;
import com.inventory.pricing.service.InventoryPricingAdapter;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.rest.dto.request.UpdateInventoryRequest;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** The pricing names in a lot update's clearFields reach the pricing record; lot names do not. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InventoryPricingWriteHandlerClearFieldsTest {

  @Mock private InventoryPricingAdapter pricingPort;
  @Mock private ShopRepository shopRepository;
  @InjectMocks private InventoryPricingWriteHandler handler;

  @AfterEach
  void clearContext() {
    InventoryPricingContext.clear();
  }

  private static Inventory savedLot() {
    Inventory lot = new Inventory();
    lot.setId("lot-1");
    lot.setShopId("s1");
    lot.setPricingId("pr-1");
    return lot;
  }

  @Test
  void pricingClearsAloneUpdateThePricingRecord() {
    UpdateInventoryRequest req = new UpdateInventoryRequest();
    req.setClearFields(new LinkedHashSet<>(
        List.of("location", "saleAdditionalDiscount", "purchaseScheme")));
    InventoryPricingContext.setUpdate(req, "owner-1");

    handler.persistOnSave(savedLot());

    ArgumentCaptor<PricingUpdateCommand> cmd = ArgumentCaptor.forClass(PricingUpdateCommand.class);
    verify(pricingPort).update(eq("pr-1"), cmd.capture(), eq("owner-1"));
    assertEquals(Set.of("saleAdditionalDiscount", "purchaseScheme"), cmd.getValue().getClearFields());
  }

  @Test
  void lotClearsAloneLeaveThePricingRecordAlone() {
    UpdateInventoryRequest req = new UpdateInventoryRequest();
    req.setClearFields(Set.of("location", "thresholdCount"));
    InventoryPricingContext.setUpdate(req, "owner-1");

    handler.persistOnSave(savedLot());

    verify(pricingPort, never()).update(any(), any(), any());
  }
}
