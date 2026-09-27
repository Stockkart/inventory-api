package com.inventory.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.audit.AuditEntry;
import com.inventory.common.audit.AuditService;
import com.inventory.common.audit.AuditSource;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.mapper.ShopMapper;
import com.inventory.product.rest.dto.request.ShopApprovalRequest;
import com.inventory.product.validation.ShopValidator;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ShopServiceApprovalAuditTest {

  private static final String SHOP_ID = "64b7f0c2a1b2c3d4e5f60718";

  @Mock private ShopRepository shopRepository;
  @Mock private ShopMapper shopMapper;
  @Mock private ShopValidator shopValidator;
  @Mock private AuditService auditService;

  @InjectMocks private ShopService shopService;

  @Test
  void approvalRecordsActorAndBeforeAfterState() {
    Shop shop = new Shop();
    shop.setShopId(SHOP_ID);
    shop.setStatus("PENDING");
    shop.setUserLimit(1);
    when(shopRepository.findById(SHOP_ID)).thenReturn(Optional.of(shop));
    when(shopRepository.save(any(Shop.class))).thenAnswer(inv -> inv.getArgument(0));

    ShopApprovalRequest request = new ShopApprovalRequest();
    request.setApprove(true);
    request.setUserLimit(5);
    shopService.approve(SHOP_ID, request, "admin-1");

    ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
    verify(auditService).record(captor.capture());
    AuditEntry entry = captor.getValue();
    assertThat(entry.getActorUserId()).isEqualTo("admin-1");
    assertThat(entry.getAction()).isEqualTo("SHOP_APPROVED");
    assertThat(entry.getTargetId()).isEqualTo(SHOP_ID);
    assertThat(entry.getSource()).isEqualTo(AuditSource.ADMIN_UI);
    assertThat(entry.getBefore()).containsEntry("status", "PENDING").containsEntry("userLimit", 1);
    assertThat(entry.getAfter()).containsEntry("status", "ACTIVE").containsEntry("userLimit", 5);
  }

  @Test
  void noOpApprovalWritesNoAudit() {
    Shop shop = new Shop();
    shop.setShopId(SHOP_ID);
    shop.setStatus("ACTIVE");
    when(shopRepository.findById(SHOP_ID)).thenReturn(Optional.of(shop));

    ShopApprovalRequest request = new ShopApprovalRequest();
    request.setApprove(true);
    request.setUserLimit(5);
    shopService.approve(SHOP_ID, request, "admin-1");

    verify(auditService, never()).record(any());
  }
}
