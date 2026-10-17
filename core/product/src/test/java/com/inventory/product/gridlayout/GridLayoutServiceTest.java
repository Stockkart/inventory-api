package com.inventory.product.gridlayout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.GridLayoutDocument;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.GridLayoutRepository;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.rest.dto.request.GridLayoutChangeRequest;
import com.inventory.product.rest.dto.response.GridLayoutResponse;
import com.inventory.product.service.vertical.SchemaLoader;
import com.inventory.product.validation.ShopValidator;
import com.inventory.user.service.RbacService;
import com.inventory.user.service.UserShopMembershipService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

class GridLayoutServiceTest {

  private static final String SHOP = "shop-1";
  private static final String ADMIN = "admin-1";
  private static final String CASHIER = "cashier-1";
  private static final String GRID = GridIds.PRODUCT_ENTRY;

  /** Stored documents keyed by shop/grid, with Spring Data's optimistic-lock semantics. */
  private final Map<String, GridLayoutDocument> store = new HashMap<>();
  /** Runs once just before the next save, to simulate another member saving in between. */
  private final AtomicReference<Runnable> beforeNextSave = new AtomicReference<>();
  private GridLayoutRepository repository;
  private GridLayoutService service;

  @BeforeEach
  void setUp() {
    repository = mock(GridLayoutRepository.class);
    when(repository.findByShopIdAndGridId(anyString(), anyString()))
        .thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0) + "/" + inv.getArgument(1))).map(GridLayoutServiceTest::copy));
    when(repository.save(any(GridLayoutDocument.class))).thenAnswer(inv -> save(inv.getArgument(0)));
    doAnswer(inv -> store.remove(inv.getArgument(0) + "/" + inv.getArgument(1)))
        .when(repository)
        .deleteByShopIdAndGridId(anyString(), anyString());

    ShopRepository shops = mock(ShopRepository.class);
    Shop shop = new Shop();
    shop.setShopId(SHOP);
    shop.setVerticalId("medical");
    when(shops.findById(SHOP)).thenReturn(Optional.of(shop));
    SchemaLoader schemaLoader = mock(SchemaLoader.class);
    when(schemaLoader.load(anyString(), any())).thenReturn(GridLayoutTestFixtures.schema());
    UserShopMembershipService membership = mock(UserShopMembershipService.class);
    when(membership.hasAccess(anyString(), anyString())).thenReturn(true);
    RbacService rbac = mock(RbacService.class);
    when(rbac.canManageShopLayouts(ADMIN, SHOP)).thenReturn(true);
    when(rbac.canManageShopLayouts(CASHIER, SHOP)).thenReturn(false);
    doThrow(new BaseException(ErrorCode.ACCESS_DENIED, "denied"))
        .when(rbac)
        .requireShopLayoutManagement(eq(CASHIER), anyString());

    service =
        new GridLayoutService(
            repository,
            shops,
            schemaLoader,
            new GridColumnCatalog(),
            new GridLayoutChangeApplier(),
            new GridLayoutResolver(),
            membership,
            rbac,
            new ShopValidator());
  }

  @Test
  void unsavedShopGetsTheDefault() {
    GridLayoutResponse res = service.get(SHOP, ADMIN, GRID);
    assertThat(res.isDefault()).isTrue();
    assertThat(res.revision()).isNull();
    assertThat(res.canEdit()).isTrue();
    assertThat(res.columns()).hasSameSizeAs(res.catalog());
    verify(repository, never()).save(any());
  }

  @Test
  void adminChangeIsSeenByEveryMember() {
    service.change(SHOP, ADMIN, GRID, change("RESIZE", "product", 320, null, null));

    GridLayoutResponse cashierView = service.get(SHOP, CASHIER, GRID);
    assertThat(cashierView.isDefault()).isFalse();
    assertThat(cashierView.canEdit()).isFalse();
    assertThat(cashierView.updatedByUserId()).isEqualTo(ADMIN);
    assertThat(cashierView.columns()).contains(new GridColumnState("product", 320, false));
  }

  @Test
  void cashierCannotChangeOrReset() {
    assertThatThrownBy(() -> service.change(SHOP, CASHIER, GRID, change("RESIZE", "qty", 90, null, null)))
        .isInstanceOf(BaseException.class);
    assertThatThrownBy(() -> service.reset(SHOP, CASHIER, GRID)).isInstanceOf(BaseException.class);
    verify(repository, never()).save(any());
  }

  @Test
  void concurrentChangesToDifferentColumnsBothSurvive() {
    service.change(SHOP, ADMIN, GRID, change("RESIZE", "qty", 90, null, null));
    // Another member hides packaging between our read and our write.
    beforeNextSave.set(
        () -> {
          GridLayoutDocument theirs = copy(store.get(SHOP + "/" + GRID));
          theirs.getColumns().replaceAll(
              c -> c.getKey().equals("packaging") ? new GridLayoutDocument.ColumnDoc("packaging", c.getWidthPx(), true) : c);
          save(theirs);
        });

    GridLayoutResponse res = service.change(SHOP, ADMIN, GRID, change("RESIZE", "product", 330, null, null));

    assertThat(res.columns()).contains(new GridColumnState("product", 330, false));
    assertThat(res.columns()).anyMatch(c -> c.key().equals("packaging") && c.hidden());
    assertThat(res.columns()).contains(new GridColumnState("qty", 90, false));
    assertThat(res.revision()).isEqualTo(2L);
  }

  @Test
  void rejectedChangeWritesNothing() {
    assertThatThrownBy(
            () -> service.change(SHOP, ADMIN, GRID, change("SET_HIDDEN", "qty", null, true, null)))
        .isInstanceOf(ValidationException.class);
    verify(repository, never()).save(any());
  }

  @Test
  void resetReturnsToTheDefault() {
    service.change(SHOP, ADMIN, GRID, change("SET_HIDDEN", "hsn", null, true, null));
    GridLayoutResponse res = service.reset(SHOP, ADMIN, GRID);
    assertThat(res.isDefault()).isTrue();
    assertThat(service.get(SHOP, ADMIN, GRID).isDefault()).isTrue();
  }

  @Test
  void wireShapeCarriesRulesAndEditRight() throws Exception {
    String json =
        JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build()
            .writeValueAsString(service.get(SHOP, CASHIER, GRID));
    assertThat(json)
        .contains("\"isDefault\":true")
        .contains("\"canEdit\":false")
        .contains("\"hideable\":false")
        .contains("\"position\":\"FIRST\"");
  }

  private GridLayoutDocument save(GridLayoutDocument doc) {
    Runnable hook = beforeNextSave.getAndSet(null);
    if (hook != null) {
      hook.run();
    }
    String key = doc.getShopId() + "/" + doc.getGridId();
    GridLayoutDocument current = store.get(key);
    Long currentRevision = current == null ? null : current.getRevision();
    if (!java.util.Objects.equals(currentRevision, doc.getRevision())) {
      throw new OptimisticLockingFailureException("stale revision");
    }
    GridLayoutDocument stored = copy(doc);
    stored.setRevision(currentRevision == null ? 0L : currentRevision + 1);
    store.put(key, stored);
    return copy(stored);
  }

  private static GridLayoutDocument copy(GridLayoutDocument d) {
    List<GridLayoutDocument.ColumnDoc> cols = new ArrayList<>();
    d.getColumns().forEach(c -> cols.add(new GridLayoutDocument.ColumnDoc(c.getKey(), c.getWidthPx(), c.getHidden())));
    return new GridLayoutDocument(
        d.getId(), d.getShopId(), d.getGridId(), d.getRevision(), cols, d.getUpdatedAt(), d.getUpdatedByUserId());
  }

  private static GridLayoutChangeRequest change(
      String type, String key, Integer width, Boolean hidden, String beforeKey) {
    return new GridLayoutChangeRequest(type, key, width, hidden, beforeKey);
  }
}
