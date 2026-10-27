package com.inventory.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.InsufficientStockException;
import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.mapper.InventoryMapper;
import com.inventory.product.util.PurchaseItemRefs;
import com.inventory.reminders.api.InventoryAlerts;
import com.mongodb.client.result.UpdateResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Completing a bill takes stock with one guarded update per lot ("take n only if n is there"),
 * puts back what it took when a later lot fails, and keeps the old save for lots with a missing
 * count.
 */
class CheckoutServiceStockTakeTest {

  private static final String SHOP = "shop-1";
  private static final String L1 = "aaaaaaaaaaaaaaaaaaaaaaa1";
  private static final String L2 = "aaaaaaaaaaaaaaaaaaaaaaa2";

  private InventoryRepository inventoryRepository;
  private MongoTemplate template;
  private InventoryAlerts events;
  private InventoryMapper inventoryMapper;
  private CheckoutService service;

  @BeforeEach
  void setUp() {
    inventoryRepository = mock(InventoryRepository.class);
    template = mock(MongoTemplate.class);
    events = mock(InventoryAlerts.class);
    inventoryMapper = mock(InventoryMapper.class);
    service = new CheckoutService();
    ReflectionTestUtils.setField(service, "inventoryRepository", inventoryRepository);
    ReflectionTestUtils.setField(service, "mongoTemplate", template);
    ReflectionTestUtils.setField(service, "eventService", events);
    ReflectionTestUtils.setField(service, "inventoryMapper", inventoryMapper);
  }

  private static Inventory lot(String id, Integer base, String display) {
    Inventory inv = new Inventory();
    inv.setId(id);
    inv.setShopId(SHOP);
    inv.setName("Lot " + id);
    inv.setCurrentBaseCount(base);
    inv.setSoldBaseCount(base == null ? null : 5);
    inv.setCurrentCount(display == null ? null : new BigDecimal(display));
    inv.setSoldCount(display == null ? null : new BigDecimal("5"));
    inv.setThresholdCount(0);
    return inv;
  }

  private static PurchaseItem line(String lotId, int base) {
    PurchaseItem i = new PurchaseItem();
    PurchaseItemRefs.applyInventoryLine(i, lotId);
    i.setBaseQuantity(base);
    i.setQuantity(BigDecimal.valueOf(base));
    return i;
  }

  private static Purchase bill(PurchaseItem... lines) {
    Purchase p = new Purchase();
    p.setId("p-1");
    p.setShopId(SHOP);
    p.setItems(new ArrayList<>(List.of(lines)));
    return p;
  }

  private void complete(Purchase p) {
    ReflectionTestUtils.invokeMethod(service, "updateInventoryForCompletedPurchase", p);
  }

  private static UpdateResult matched(long n) {
    return UpdateResult.acknowledged(n, n, null);
  }

  @Test
  void eachLotIsTakenWithOneGuardedUpdateOfOnlyItsCounts() {
    when(inventoryRepository.findAllById(anyIterable())).thenReturn(List.of(lot(L1, 10, "10"), lot(L2, 4, "4")));
    when(template.updateFirst(any(Query.class), any(Update.class), eq(Inventory.class))).thenReturn(matched(1));

    complete(bill(line(L1, 2), line(L2, 1), line(L1, 3))); // L1 on two lines

    ArgumentCaptor<Query> q = ArgumentCaptor.forClass(Query.class);
    ArgumentCaptor<Update> u = ArgumentCaptor.forClass(Update.class);
    verify(template, times(2)).updateFirst(q.capture(), u.capture(), eq(Inventory.class));
    Document guard = q.getAllValues().get(0).getQueryObject();
    assertThat(guard.get("_id")).isEqualTo(L1);
    assertThat(guard.get("shopId")).isEqualTo(SHOP);
    assertThat(guard.get("currentBaseCount")).isEqualTo(new Document("$gte", 5));
    Document inc = (Document) u.getAllValues().get(0).getUpdateObject().get("$inc");
    assertThat(inc.get("currentBaseCount")).isEqualTo(-5);
    assertThat(inc.get("soldBaseCount")).isEqualTo(5);
    assertThat(((Decimal128) inc.get("currentCount")).bigDecimalValue()).isEqualByComparingTo("-5");
    assertThat(((Decimal128) inc.get("soldCount")).bigDecimalValue()).isEqualByComparingTo("5");
    assertThat(u.getAllValues().get(0).getUpdateObject().keySet()).containsExactly("$inc");
    verify(inventoryRepository, never()).save(any());
    verify(inventoryRepository, times(1)).findAllById(anyIterable());
    verify(inventoryRepository, never()).findById(any());
  }

  @Test
  void aLotSoldElsewhereMeanwhileFailsAndWhatWasTakenIsPutBack() {
    when(inventoryRepository.findAllById(anyIterable())).thenReturn(List.of(lot(L1, 10, "10"), lot(L2, 1, "1")));
    when(template.updateFirst(any(Query.class), any(Update.class), eq(Inventory.class)))
        .thenReturn(matched(1))   // L1 taken
        .thenReturn(matched(0))   // L2: the last unit went to another bill a moment ago
        .thenReturn(matched(1));  // put back L1
    when(inventoryRepository.findById(L2)).thenReturn(Optional.of(lot(L2, 0, "0")));

    assertThatThrownBy(() -> complete(bill(line(L1, 2), line(L2, 1))))
        .isInstanceOf(InsufficientStockException.class)
        .hasMessageContaining("Available: 0, Required in base units: 1");

    ArgumentCaptor<Query> q = ArgumentCaptor.forClass(Query.class);
    ArgumentCaptor<Update> u = ArgumentCaptor.forClass(Update.class);
    verify(template, times(3)).updateFirst(q.capture(), u.capture(), eq(Inventory.class));
    assertThat(q.getAllValues().get(2).getQueryObject().get("_id")).isEqualTo(L1);
    Document putBack = (Document) u.getAllValues().get(2).getUpdateObject().get("$inc");
    assertThat(putBack.get("currentBaseCount")).isEqualTo(2);
    assertThat(putBack.get("soldBaseCount")).isEqualTo(-2);
    verify(events, never()).recordAndBroadcastInventoryLow(any());
  }

  @Test
  void notEnoughStockFailsBeforeAnythingIsChanged() {
    when(inventoryRepository.findAllById(anyIterable())).thenReturn(List.of(lot(L1, 10, "10"), lot(L2, 1, "1")));

    assertThatThrownBy(() -> complete(bill(line(L1, 2), line(L2, 3))))
        .isInstanceOf(InsufficientStockException.class)
        .hasMessageContaining("Available: 1, Required in base units: 3");
    verify(template, never()).updateFirst(any(Query.class), any(Update.class), eq(Inventory.class));
  }

  @Test
  void anotherShopsLotIsRefusedBeforeAnythingIsChanged() {
    Inventory foreign = lot(L1, 10, "10");
    foreign.setShopId("shop-2");
    when(inventoryRepository.findAllById(anyIterable())).thenReturn(List.of(foreign));

    assertThatThrownBy(() -> complete(bill(line(L1, 1))))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("does not belong to shop");
    verify(template, never()).updateFirst(any(Query.class), any(Update.class), eq(Inventory.class));
  }

  @Test
  void aLotWithAMissingCountKeepsTheOldSave() {
    Inventory legacy = lot(L1, null, "7"); // display count only
    when(inventoryRepository.findAllById(anyIterable())).thenReturn(List.of(legacy));

    complete(bill(line(L1, 2)));

    ArgumentCaptor<Inventory> saved = ArgumentCaptor.forClass(Inventory.class);
    verify(inventoryRepository).save(saved.capture());
    assertThat(saved.getValue().getCurrentBaseCount()).isEqualTo(5);
    assertThat(saved.getValue().getCurrentCount()).isEqualByComparingTo("5");
    assertThat(saved.getValue().getSoldBaseCount()).isEqualTo(7); // 5 already sold (from the display count) + 2
    verify(template, never()).updateFirst(any(Query.class), any(Update.class), eq(Inventory.class));
  }

  @Test
  void lowStockAlertIsRaisedOncePerLotWithTheNewCount() {
    Inventory l1 = lot(L1, 6, "6");
    l1.setThresholdCount(5);
    when(inventoryRepository.findAllById(anyIterable())).thenReturn(List.of(l1));
    when(template.updateFirst(any(Query.class), any(Update.class), eq(Inventory.class))).thenReturn(matched(1));

    complete(bill(line(L1, 1), line(L1, 1)));

    ArgumentCaptor<Inventory> alerted = ArgumentCaptor.forClass(Inventory.class);
    verify(inventoryMapper, times(1)).toInventoryLowEventDto(alerted.capture(), eq(5));
    assertThat(alerted.getValue().getCurrentBaseCount()).isEqualTo(4);
    verify(events, times(1)).recordAndBroadcastInventoryLow(any());
  }

  @Test
  void menuLinesAreNotStock() {
    PurchaseItem menu = new PurchaseItem();
    menu.setSellMode("menu");
    menu.setSellableRef("menu:m1");
    menu.setBaseQuantity(3);

    complete(bill(menu));

    verify(inventoryRepository, never()).findAllById(anyIterable());
    verify(template, never()).updateFirst(any(Query.class), any(Update.class), eq(Inventory.class));
  }
}
