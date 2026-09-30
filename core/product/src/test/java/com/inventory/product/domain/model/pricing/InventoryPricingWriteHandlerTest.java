package com.inventory.product.domain.model.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.pricing.rest.dto.request.PricingCreateCommand;
import com.inventory.pricing.service.InventoryPricingAdapter;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.model.enums.ShopType;
import com.inventory.product.domain.repository.ShopRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InventoryPricingWriteHandlerTest {

  private static final String SHOP = "shop1";

  @Mock
  private InventoryPricingAdapter pricingPort;

  @Mock
  private ShopRepository shopRepository;

  @InjectMocks
  private InventoryPricingWriteHandler handler;

  @Test
  void persistOnSave_cafeShopTypedRetailer_sendsCafeShapeSoPricingIsCreated() {
    // Cafe shops are registered with shopType RETAILER. The retail branch mirrors selling
    // price into MRP/PTR, which CafePricingPolicy rejects -- and the swallowed rejection
    // left the inventory with no pricing record at all.
    givenShop(ShopType.RETAILER, "cafe");
    when(pricingPort.create(org.mockito.ArgumentMatchers.any())).thenReturn("pricing1");
    Inventory inventory = newInventory("18", "20");

    handler.persistOnSave(inventory);

    PricingCreateCommand cmd = capturedCreate();
    assertEquals("cafe", cmd.getVerticalId());
    assertEquals(new BigDecimal("18"), cmd.getCostPrice());
    assertEquals(new BigDecimal("20"), cmd.getSellingPrice());
    assertNull(cmd.getMaximumRetailPrice());
    assertNull(cmd.getPriceToRetail());
    assertNull(cmd.getDefaultRate());
    assertEquals("pricing1", inventory.getPricingId());
  }

  @Test
  void persistOnSave_retailerShopWithoutCafeVertical_stillMirrorsSellingPrice() {
    givenShop(ShopType.RETAILER, "medical");
    when(pricingPort.create(org.mockito.ArgumentMatchers.any())).thenReturn("pricing1");

    handler.persistOnSave(newInventory("18", "20"));

    PricingCreateCommand cmd = capturedCreate();
    assertEquals(new BigDecimal("20"), cmd.getSellingPrice());
    assertEquals(new BigDecimal("20"), cmd.getMaximumRetailPrice());
    assertEquals(new BigDecimal("20"), cmd.getPriceToRetail());
    assertEquals("priceToRetail", cmd.getDefaultRate());
  }

  private void givenShop(ShopType type, String verticalId) {
    Shop shop = new Shop();
    shop.setShopType(type);
    shop.setVerticalId(verticalId);
    when(shopRepository.findById(SHOP)).thenReturn(Optional.of(shop));
  }

  private static Inventory newInventory(String cost, String selling) {
    Inventory inventory = new Inventory();
    inventory.setShopId(SHOP);
    inventory.setCostPrice(new BigDecimal(cost));
    inventory.setSellingPrice(new BigDecimal(selling));
    return inventory;
  }

  private PricingCreateCommand capturedCreate() {
    ArgumentCaptor<PricingCreateCommand> captor = ArgumentCaptor.forClass(PricingCreateCommand.class);
    verify(pricingPort).create(captor.capture());
    return captor.getValue();
  }
}
