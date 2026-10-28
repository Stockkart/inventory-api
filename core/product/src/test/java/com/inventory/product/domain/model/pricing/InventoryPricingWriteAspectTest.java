package com.inventory.product.domain.model.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.pricing.mapper.PricingMapper;
import com.inventory.pricing.mapper.PricingMapperImpl;
import com.inventory.pricing.rest.dto.request.UpdatePricingRequest;
import com.inventory.pricing.service.InventoryPricingAdapterImpl;
import com.inventory.pricing.service.PricingService;
import com.inventory.pricing.service.api.InventoryPricingImpl;
import com.inventory.pricing.validation.PricingValidator;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.rest.dto.request.UpdateInventoryRequest;
import java.math.BigDecimal;
import java.util.Optional;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InventoryPricingWriteAspectTest {

  @Mock private PricingService pricingService;
  @Mock private ShopRepository shopRepository;
  @Mock private ProceedingJoinPoint joinPoint;
  @Spy private PricingMapper pricingMapper = new PricingMapperImpl();
  @Spy private PricingValidator pricingValidator = new PricingValidator();
  @InjectMocks private InventoryPricingAdapterImpl adapter;

  private final InventoryPricingWriteHandler handler = new InventoryPricingWriteHandler();
  private final InventoryPricingWriteAspect aspect = new InventoryPricingWriteAspect();

  @BeforeEach
  void setUp() {
    // the real pricing path, through the api the handler now uses
    ReflectionTestUtils.setField(handler, "pricingPort", new InventoryPricingImpl(adapter));
    ReflectionTestUtils.setField(handler, "shopRepository", shopRepository);
    ReflectionTestUtils.setField(aspect, "handler", handler);
    when(shopRepository.findById(anyString())).thenReturn(Optional.empty());
  }

  @Test
  void inventoryUpdateCarriesUserIdToPricing() throws Throwable {
    UpdateInventoryRequest request = new UpdateInventoryRequest();
    request.setPriceToRetail(new BigDecimal("110"));
    Inventory inventory = new Inventory();
    inventory.setId("inv-1");
    inventory.setShopId("shop-1");
    inventory.setPricingId("pr-1");
    when(joinPoint.getArgs()).thenReturn(new Object[] {"inv-1", request, "shop-1", "user-7"});
    when(joinPoint.proceed()).thenAnswer(i -> {
      handler.persistOnSave(inventory);
      return null;
    });

    aspect.setUpdateContext(joinPoint);

    ArgumentCaptor<UpdatePricingRequest> captor = ArgumentCaptor.forClass(UpdatePricingRequest.class);
    verify(pricingService).update(eq("pr-1"), captor.capture(), isNull(), eq("user-7"));
    assertEquals(new BigDecimal("110"), captor.getValue().getPriceToRetail());
    assertNull(InventoryPricingContext.get());
  }

  @Test
  void accessDeniedOnInventoryUpdatePropagatesAndInventoryIsNotSaved() throws Throwable {
    UpdateInventoryRequest request = new UpdateInventoryRequest();
    request.setPriceToRetail(new BigDecimal("110"));
    Inventory inventory = new Inventory();
    inventory.setId("inv-1");
    inventory.setShopId("shop-1");
    inventory.setPricingId("pr-1");
    ProceedingJoinPoint saveCall = org.mockito.Mockito.mock(ProceedingJoinPoint.class);
    when(saveCall.getArgs()).thenReturn(new Object[] {inventory});
    when(joinPoint.getArgs()).thenReturn(new Object[] {"inv-1", request, "shop-1", "user-7"});
    when(joinPoint.proceed()).thenAnswer(i -> aspect.persistOnSave(saveCall));
    org.mockito.Mockito.doThrow(new BaseException(ErrorCode.ACCESS_DENIED, "You do not have permission to edit rates"))
        .when(pricingService).update(anyString(), any(), any(), any());

    BaseException e = assertThrows(BaseException.class, () -> aspect.setUpdateContext(joinPoint));

    assertEquals(ErrorCode.ACCESS_DENIED, e.getErrorCode());
    verify(saveCall, never()).proceed(any());
    assertNull(InventoryPricingContext.get());
  }

  @Test
  void otherPricingFailuresAreStillSwallowed() throws Throwable {
    UpdateInventoryRequest request = new UpdateInventoryRequest();
    request.setPriceToRetail(new BigDecimal("110"));
    Inventory inventory = new Inventory();
    inventory.setId("inv-1");
    inventory.setShopId("shop-1");
    inventory.setPricingId("pr-1");
    when(joinPoint.getArgs()).thenReturn(new Object[] {"inv-1", request, "shop-1", "user-7"});
    when(joinPoint.proceed()).thenAnswer(i -> {
      handler.persistOnSave(inventory);
      return null;
    });
    // A pricing 400 now reaches the caller (InventoryPricingWriteHandlerRateRulesTest); any
    // other pricing failure is still logged and swallowed.
    org.mockito.Mockito.doThrow(new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error updating pricing"))
        .when(pricingService).update(anyString(), any(), any(), any());

    aspect.setUpdateContext(joinPoint);
  }

  @Test
  void createPassesNoUserId() {
    Inventory inventory = new Inventory();
    inventory.setShopId("shop-1");
    inventory.setPriceToRetail(new BigDecimal("100"));
    when(pricingService.createAndReturnEntity(any())).thenReturn(new com.inventory.pricing.domain.model.Pricing());

    handler.persistOnSave(inventory);

    verify(pricingService).createAndReturnEntity(any());
  }
}
