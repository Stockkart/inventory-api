package com.inventory.product.service.vertical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import com.inventory.pluginengine.cart.CartLineSnapshot;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.mapper.PurchaseMapper;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for the mapper hop flagged in task-3: {@code toPurchaseItem} copies fields one
 * by one, so a missed line silently drops the kitchen station and every ticket would route to
 * KITCHEN with no error. This pins {@code department} to survive the copy.
 */
class CartLineSnapshotMapperTest {

  // PurchaseMapper is an abstract MapStruct class with @Autowired collaborators; mocking it is
  // the smallest reachable seam — enrichPurchaseItemMargin is void, so the default Mockito
  // no-op stub is exactly what toPurchaseItem needs here.
  private final PurchaseMapper purchaseMapper = mock(PurchaseMapper.class);
  private final CartLineSnapshotMapper mapper = new CartLineSnapshotMapper(purchaseMapper);

  @Test
  void departmentSurvivesTheCopyToPurchaseItem() {
    CartLineSnapshot snapshot = CartLineSnapshot.builder().department("BAR").build();

    PurchaseItem item = mapper.toPurchaseItem(snapshot);

    assertEquals("BAR", item.getDepartment());
  }

  @Test
  void nullDepartmentMapsToNullRatherThanThrowing() {
    CartLineSnapshot snapshot = CartLineSnapshot.builder().department(null).build();

    PurchaseItem item = mapper.toPurchaseItem(snapshot);

    assertNull(item.getDepartment());
  }

  @Test
  void noteSurvivesTheCopyToPurchaseItem() {
    CartLineSnapshot snapshot = CartLineSnapshot.builder().note("no onion").build();

    PurchaseItem item = mapper.toPurchaseItem(snapshot);

    assertEquals("no onion", item.getNote());
  }

  @Test
  void nullNoteMapsToNullRatherThanThrowing() {
    CartLineSnapshot snapshot = CartLineSnapshot.builder().note(null).build();

    PurchaseItem item = mapper.toPurchaseItem(snapshot);

    assertNull(item.getNote());
  }
}
