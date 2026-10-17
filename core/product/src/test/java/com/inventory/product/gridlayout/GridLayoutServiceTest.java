package com.inventory.product.gridlayout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.GridLayoutDocument;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.GridLayoutRepository;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.rest.dto.request.SaveGridLayoutRequest;
import com.inventory.product.rest.dto.request.SaveGridLayoutRequest.ColumnSpec;
import com.inventory.product.rest.dto.response.GridLayoutResponse;
import com.inventory.product.service.vertical.SchemaLoader;
import com.inventory.product.validation.ShopValidator;
import com.inventory.user.service.UserShopMembershipService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GridLayoutServiceTest {

  private static final String SHOP = "shop-1";
  private static final String USER = "user-1";
  private static final String GRID = GridIds.PRODUCT_ENTRY;

  private final Map<String, GridLayoutDocument> store = new HashMap<>();
  private GridLayoutRepository repository;
  private GridLayoutService service;

  @BeforeEach
  void setUp() {
    repository = mock(GridLayoutRepository.class);
    when(repository.findByShopIdAndGridId(anyString(), anyString()))
        .thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0) + "/" + inv.getArgument(1))));
    when(repository.save(any(GridLayoutDocument.class)))
        .thenAnswer(
            inv -> {
              GridLayoutDocument doc = inv.getArgument(0);
              store.put(doc.getShopId() + "/" + doc.getGridId(), doc);
              return doc;
            });
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

    service =
        new GridLayoutService(
            repository,
            shops,
            schemaLoader,
            new GridColumnCatalog(),
            new GridLayoutValidator(),
            new GridLayoutResolver(),
            membership,
            new ShopValidator());
  }

  @Test
  void unsavedShopGetsTheDefault() {
    GridLayoutResponse res = service.get(SHOP, USER, GRID);
    assertThat(res.isDefault()).isTrue();
    assertThat(res.columns()).hasSameSizeAs(res.catalog());
    verify(repository, never()).save(any());
  }

  @Test
  void savedLayoutIsSharedByEveryMember() {
    service.save(
        SHOP,
        USER,
        GRID,
        new SaveGridLayoutRequest(
            List.of(new ColumnSpec("product", 320, false), new ColumnSpec("hsn", 100, true))));

    GridLayoutResponse other = service.get(SHOP, "user-2", GRID);
    assertThat(other.isDefault()).isFalse();
    assertThat(other.updatedByUserId()).isEqualTo(USER);
    assertThat(other.columns()).contains(new GridColumnState("product", 320, false));
    assertThat(other.columns()).contains(new GridColumnState("hsn", 100, true));
  }

  @Test
  void rejectedSaveWritesNothing() {
    assertThatThrownBy(
            () ->
                service.save(
                    SHOP,
                    USER,
                    GRID,
                    new SaveGridLayoutRequest(List.of(new ColumnSpec("qty", 80, true)))))
        .isInstanceOf(ValidationException.class);
    verify(repository, never()).save(any());
  }

  @Test
  void resetReturnsToTheDefault() {
    service.save(
        SHOP, USER, GRID, new SaveGridLayoutRequest(List.of(new ColumnSpec("hsn", 100, true))));
    GridLayoutResponse res = service.reset(SHOP, USER, GRID);
    assertThat(res.isDefault()).isTrue();
    assertThat(service.get(SHOP, USER, GRID).isDefault()).isTrue();
  }

  @Test
  void wireShapeUsesIsDefault() throws Exception {
    String json =
        JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build()
            .writeValueAsString(service.get(SHOP, USER, GRID));
    assertThat(json).contains("\"isDefault\":true").contains("\"defaultWidthPx\":48");
  }
}
