package com.inventory.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.service.vertical.InventoryVerticalExtensionHandler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** A printed document reads its lots once, not lot + product + pricing + extension per line. */
class DocumentLotsTest {

  private InventoryRepository inventories;
  private InventoryVerticalExtensionHandler extensions;
  private DocumentLots documentLots;

  @BeforeEach
  void setUp() {
    inventories = mock(InventoryRepository.class);
    extensions = mock(InventoryVerticalExtensionHandler.class);
    documentLots = new DocumentLots(inventories, extensions);
  }

  private static Inventory lot(String id, String shop) {
    Inventory inv = new Inventory();
    inv.setId(id);
    inv.setShopId(shop);
    return inv;
  }

  @Test
  @SuppressWarnings("unchecked")
  void oneLotReadAndOneExtensionReadPerShopForTheWholeDocument() {
    when(inventories.findAllById(any())).thenReturn(List.of(lot("a", "s1"), lot("b", "s1"), lot("c", "s2")));
    when(extensions.loadExtensionFieldsBatch(eq("s1"), anyList()))
        .thenReturn(Map.of("a", Map.of("batchNo", "B-A")));
    when(extensions.loadExtensionFieldsBatch(eq("s2"), anyList()))
        .thenReturn(Map.of("c", Map.of("batchNo", "B-C")));

    // a line repeats a lot, one line has no lot, one lot no longer exists
    DocumentLots.Loaded loaded = documentLots.load(Arrays.asList("a", "b", "a", null, " ", "c", "gone"));

    ArgumentCaptor<Iterable<String>> ids = ArgumentCaptor.forClass(Iterable.class);
    verify(inventories, times(1)).findAllById(ids.capture());
    List<String> asked = new ArrayList<>();
    ids.getValue().forEach(asked::add);
    assertEquals(List.of("a", "b", "c", "gone"), asked);
    verify(extensions, times(1)).loadExtensionFieldsBatch(eq("s1"), eq(List.of("a", "b")));
    verify(extensions, times(1)).loadExtensionFieldsBatch(eq("s2"), eq(List.of("c")));

    assertEquals("a", loaded.lot("a").orElseThrow().getId());
    assertTrue(loaded.lot("gone").isEmpty());
    assertTrue(loaded.lot(null).isEmpty());
    assertEquals(Map.of("batchNo", "B-A"), loaded.extensionFields(loaded.lot("a").orElseThrow()));
    assertEquals(Map.of(), loaded.extensionFields(loaded.lot("b").orElseThrow())); // no extension row
    assertEquals(Map.of("batchNo", "B-C"), loaded.extensionFields(loaded.lot("c").orElseThrow()));
    assertEquals(Map.of(), loaded.extensionFields(null));
  }

  @Test
  void aDocumentWithoutLotsReadsNothing() {
    DocumentLots.Loaded loaded = documentLots.load(Arrays.asList(null, ""));

    verify(inventories, never()).findAllById(any());
    verify(extensions, never()).loadExtensionFieldsBatch(any(), any());
    assertTrue(loaded.lot("x").isEmpty());
  }

  @Test
  void extensionFieldsOfLotsAlreadyRead() {
    when(extensions.loadExtensionFieldsBatch("s1", List.of("a"))).thenReturn(Map.of("a", Map.of("k", "v")));

    Map<String, Map<String, Object>> fields =
        DocumentLots.extensionFields(extensions, Set.of(lot("a", "s1")));

    assertEquals(Map.of("a", Map.of("k", "v")), fields);
  }
}
