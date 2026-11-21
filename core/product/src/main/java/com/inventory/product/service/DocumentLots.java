package com.inventory.product.service;

import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.service.vertical.InventoryVerticalExtensionHandler;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * The lots behind a printed document's lines (invoice, credit note, debit note), read once for the
 * whole document: one {@code findAllById} (product and pricing filled in by the repository's read
 * aspects, one batch each) and one vertical-extension read per shop. Printing used to read each
 * line's lot, product, pricing and extension separately — four trips per line.
 */
@Component
public class DocumentLots {

  private final InventoryRepository inventoryRepository;
  private final InventoryVerticalExtensionHandler extensionHandler;

  public DocumentLots(InventoryRepository inventoryRepository, InventoryVerticalExtensionHandler extensionHandler) {
    this.inventoryRepository = inventoryRepository;
    this.extensionHandler = extensionHandler;
  }

  /** Lots and their vertical fields for these lot ids (blank ids ignored). */
  public Loaded load(Collection<String> lotIds) {
    Set<String> ids = new LinkedHashSet<>();
    for (String id : lotIds) {
      if (StringUtils.hasText(id)) {
        ids.add(id);
      }
    }
    Map<String, Inventory> lots = new HashMap<>();
    Map<String, Map<String, Object>> extension = new HashMap<>();
    if (ids.isEmpty()) {
      return new Loaded(lots, extension);
    }
    for (Inventory inv : inventoryRepository.findAllById(ids)) {
      if (inv != null && inv.getId() != null) {
        lots.put(inv.getId(), inv);
      }
    }
    extension.putAll(extensionFields(extensionHandler, lots.values()));
    return new Loaded(lots, extension);
  }

  /**
   * Vertical fields of these lots by lot id, one read per shop. Each lot is read under its own
   * shop, as {@code loadExtensionFields(lot.getShopId(), lot.getId())} did line by line.
   */
  public static Map<String, Map<String, Object>> extensionFields(
      InventoryVerticalExtensionHandler handler, Collection<Inventory> lots) {
    Map<String, List<String>> idsByShop = new LinkedHashMap<>();
    for (Inventory inv : lots) {
      if (inv != null && StringUtils.hasText(inv.getId())) {
        idsByShop.computeIfAbsent(inv.getShopId(), s -> new ArrayList<>()).add(inv.getId());
      }
    }
    Map<String, Map<String, Object>> out = new HashMap<>();
    idsByShop.forEach((shopId, ids) -> out.putAll(handler.loadExtensionFieldsBatch(shopId, ids)));
    return out;
  }

  /** What {@link #load} read. */
  public static final class Loaded {
    private final Map<String, Inventory> lots;
    private final Map<String, Map<String, Object>> extension;

    Loaded(Map<String, Inventory> lots, Map<String, Map<String, Object>> extension) {
      this.lots = lots;
      this.extension = extension;
    }

    /** The lot, as {@code inventoryRepository.findById(id)} would return it. */
    public Optional<Inventory> lot(String id) {
      return id == null ? Optional.empty() : Optional.ofNullable(lots.get(id));
    }

    /** The lot's vertical fields, as {@code loadExtensionFields(shopId, id)} would return them (empty if none). */
    public Map<String, Object> extensionFields(Inventory inventory) {
      if (inventory == null || inventory.getId() == null) {
        return Map.of();
      }
      return extension.getOrDefault(inventory.getId(), Map.of());
    }
  }
}
