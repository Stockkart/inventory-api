package com.inventory.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.mapper.ShopMapper;
import com.inventory.product.rest.dto.request.RegisterShopRequest;
import com.inventory.product.rest.dto.request.ShopApprovalRequest;
import com.inventory.product.rest.dto.response.ShopApprovalResponse;
import com.inventory.product.service.vertical.VerticalCatalogService;
import com.inventory.product.validation.ShopValidator;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.domain.repository.UserAccountRepository;
import com.inventory.user.service.UserShopMembershipService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** A6: only SUPER_USER approves shops. A3: a SUPER_USER account cannot register (own) a shop. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ShopServiceSuperUserTest {

  @Mock private ShopRepository shopRepository;
  @Mock private ShopMapper shopMapper;
  @Mock private UserAccountRepository userAccountRepository;
  @Mock private UserShopMembershipService membershipService;
  @Mock private VerticalCatalogService verticalCatalogService;
  @Mock private MetricsWrapper metrics;
  @Spy private ShopValidator shopValidator = new ShopValidator();
  @InjectMocks private ShopService shopService;

  private ShopApprovalRequest approveWithLimit(int userLimit) {
    ShopApprovalRequest r = new ShopApprovalRequest();
    r.setApprove(true);
    r.setUserLimit(userLimit);
    return r;
  }

  @ParameterizedTest
  @EnumSource(value = UserRole.class, names = {"OWNER", "ADMIN", "MANAGER", "CASHIER"})
  void shopPeopleCannotApproveShops(UserRole role) {
    BaseException e = assertThrows(BaseException.class,
        () -> shopService.approve("shop-1", approveWithLimit(5), role));

    assertEquals(ErrorCode.ACCESS_DENIED, e.getErrorCode());
    verifyNoInteractions(shopRepository);
  }

  @Test
  void aCallerWithNoRoleCannotApproveShops() {
    BaseException e = assertThrows(BaseException.class,
        () -> shopService.approve("shop-1", approveWithLimit(5), null));

    assertEquals(ErrorCode.ACCESS_DENIED, e.getErrorCode());
    verifyNoInteractions(shopRepository);
  }

  @Test
  void aSuperUserCanApproveAShop() {
    Shop shop = new Shop();
    shop.setShopId("shop-1");
    shop.setStatus("PENDING");
    ShopApprovalResponse mapped = new ShopApprovalResponse();
    when(shopRepository.findById("shop-1")).thenReturn(Optional.of(shop));
    when(shopRepository.save(any(Shop.class))).thenAnswer(i -> i.getArgument(0));
    when(shopMapper.toApprovalResponse(shop)).thenReturn(mapped);

    ShopApprovalResponse response = shopService.approve("shop-1", approveWithLimit(5), UserRole.SUPER_USER);

    assertSame(mapped, response);
    assertEquals("ACTIVE", shop.getStatus());
    assertEquals(5, shop.getUserLimit());
    verify(shopRepository).save(shop);
  }

  @Test
  void aSuperUserCannotRegisterAShop() {
    RegisterShopRequest request = new RegisterShopRequest();
    request.setContactEmail("staff@stockkart.example");
    doNothing().when(shopValidator).validateRegisterRequest(any());
    when(shopRepository.findAllByContactEmail(anyString())).thenReturn(List.of());
    UserAccount staff = new UserAccount();
    staff.setUserId("staff-1");
    staff.setRole(UserRole.SUPER_USER);
    when(userAccountRepository.findById("staff-1")).thenReturn(Optional.of(staff));

    assertThrows(ValidationException.class, () -> shopService.register(request, "staff-1"));
    verify(shopRepository, never()).save(any());
    verifyNoInteractions(membershipService);
  }
}
