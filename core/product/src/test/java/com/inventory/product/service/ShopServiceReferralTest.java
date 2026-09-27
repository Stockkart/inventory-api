package com.inventory.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ValidationException;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.plan.service.referral.ReferralAttributionService;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.mapper.ShopMapper;
import com.inventory.product.rest.dto.request.RegisterShopRequest;
import com.inventory.product.service.vertical.VerticalCatalogService;
import com.inventory.product.validation.ShopValidator;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.repository.UserAccountRepository;
import com.inventory.user.service.UserShopMembershipService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class ShopServiceReferralTest {

  @Mock private ShopRepository shopRepository;
  @Mock private ShopMapper shopMapper;
  @Mock private ShopValidator shopValidator;
  @Mock private UserAccountRepository userAccountRepository;
  @Mock private UserShopMembershipService membershipService;
  @Mock private VerticalCatalogService verticalCatalogService;
  @Mock private MetricsWrapper metrics;
  @Mock private ReferralAttributionService referralAttributionService;

  @InjectMocks private ShopService shopService;

  private RegisterShopRequest request;

  @BeforeEach
  void setUp() {
    request = new RegisterShopRequest();
    request.setName("New Shop");
    request.setContactEmail("new@shop.in");
    request.setReferredByCode("sk-ab2cd3");
    request.setReferredByName("Ravi");
  }

  private void registrationCollaborators() {
    when(shopRepository.findAllByContactEmail("new@shop.in")).thenReturn(List.of());
    when(userAccountRepository.findById("user-1")).thenReturn(Optional.of(new UserAccount()));
    when(shopMapper.toEntity(request)).thenReturn(new Shop());
    when(verticalCatalogService.resolveForNewShop(any()))
        .thenReturn(new VerticalCatalogService.VerticalPin("medical", "1.0.0"));
  }

  @Test
  void aNewShopGetsAReferralCodeAndItsReferrerIsRecorded() {
    registrationCollaborators();
    when(shopRepository.save(any(Shop.class))).thenAnswer(inv -> {
      Shop shop = inv.getArgument(0);
      shop.setShopId("shop-new");
      return shop;
    });

    shopService.register(request, "user-1");

    verify(referralAttributionService).checkReferral("sk-ab2cd3", "Ravi");
    verify(referralAttributionService).attribute("shop-new", "user-1", "sk-ab2cd3", "Ravi");
  }

  @Test
  void aReferralCodeCollisionDrawsAnotherCode() {
    registrationCollaborators();
    List<String> triedCodes = new ArrayList<>();
    when(shopRepository.save(any(Shop.class))).thenAnswer(inv -> {
      Shop shop = inv.getArgument(0);
      triedCodes.add(shop.getReferralCode());
      if (triedCodes.size() == 1) {
        throw new DuplicateKeyException("E11000 duplicate key error index: referralCode_unique");
      }
      shop.setShopId("shop-new");
      return shop;
    });

    shopService.register(request, "user-1");

    assertThat(triedCodes).hasSize(2).allMatch(code -> code.startsWith("SK-"));
    verify(shopRepository, times(2)).save(any(Shop.class));
  }

  @Test
  void anUnknownReferralCodeStopsRegistrationBeforeTheShopExists() {
    registrationCollaborators();
    doThrow(new ValidationException("Referral code SK-AB2CD3 does not exist"))
        .when(referralAttributionService).checkReferral(any(), any());

    assertThatThrownBy(() -> shopService.register(request, "user-1")).isInstanceOf(ValidationException.class);
    verify(shopRepository, never()).save(any(Shop.class));
  }

  @Test
  void aFailedAttributionNeverFailsRegistration() {
    registrationCollaborators();
    when(shopRepository.save(any(Shop.class))).thenAnswer(inv -> {
      Shop shop = inv.getArgument(0);
      shop.setShopId("shop-new");
      return shop;
    });
    when(referralAttributionService.attribute(eq("shop-new"), any(), any(), any()))
        .thenThrow(new IllegalStateException("mongo down"));

    shopService.register(request, "user-1");

    verify(shopMapper).toRegistrationResponse(any(Shop.class));
  }
}
