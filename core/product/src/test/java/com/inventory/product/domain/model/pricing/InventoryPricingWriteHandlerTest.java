package com.inventory.product.domain.model.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.pricing.api.InventoryPricing;
import com.inventory.pricing.api.PricingCreate;
import com.inventory.pricing.api.port.ShopRateRulesPort;
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
  private InventoryPricing pricingPort;

  @Mock
  private ShopRateRulesPort shopRateRulesPort;

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

    PricingCreate cmd = capturedCreate();
    assertEquals("cafe", cmd.verticalId());
    assertEquals(new BigDecimal("18"), cmd.costPrice());
    assertEquals(new BigDecimal("20"), cmd.sellingPrice());
    assertNull(cmd.maximumRetailPrice());
    assertNull(cmd.priceToRetail());
    assertNull(cmd.defaultRate());
    assertEquals("pricing1", inventory.getPricingId());
  }

  @Test
  void persistOnSave_retailerShopWithoutCafeVertical_stillMirrorsSellingPrice() {
    givenShop(ShopType.RETAILER, "medical");
    when(pricingPort.create(org.mockito.ArgumentMatchers.any())).thenReturn("pricing1");

    handler.persistOnSave(newInventory("18", "20"));

    PricingCreate cmd = capturedCreate();
    assertEquals(new BigDecimal("20"), cmd.sellingPrice());
    assertEquals(new BigDecimal("20"), cmd.maximumRetailPrice());
    assertEquals(new BigDecimal("20"), cmd.priceToRetail());
    assertEquals("priceToRetail", cmd.defaultRate());
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

  private PricingCreate capturedCreate() {
    ArgumentCaptor<PricingCreate> captor = ArgumentCaptor.forClass(PricingCreate.class);
    verify(pricingPort).create(captor.capture());
    return captor.getValue();
  }
}
