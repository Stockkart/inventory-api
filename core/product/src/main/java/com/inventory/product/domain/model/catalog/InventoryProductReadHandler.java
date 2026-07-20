package com.inventory.product.domain.model.catalog;

import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Product;
import com.inventory.product.domain.repository.ProductRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hydrates catalog identity fields on {@link Inventory} from the owning {@link Product}, making
 * Product the source of truth for identity while inventory keeps a denormalized copy for search.
 *
 * <p>Only overwrites when a {@code productId} is present; legacy rows without one keep their stored
 * identity (dual-read window). Wired by {@link InventoryProductReadAspect} at the repository seam so
 * call sites need no changes.
 */
@Slf4j
@Component
public class InventoryProductReadHandler {

  @Autowired private ProductRepository productRepository;

  public void enrich(Inventory inventory) {
    if (inventory == null || !StringUtils.hasText(inventory.getProductId())) {
      return;
    }
    productRepository.findById(inventory.getProductId())
        .ifPresent(product -> apply(inventory, product));
  }

  public void enrich(List<Inventory> inventories) {
    if (inventories == null || inventories.isEmpty()) {
      return;
    }
    Set<String> ids = new LinkedHashSet<>();
    for (Inventory inv : inventories) {
      if (inv != null && StringUtils.hasText(inv.getProductId())) {
        ids.add(inv.getProductId());
      }
    }
    if (ids.isEmpty()) {
      return;
    }
    Map<String, Product> byId = new HashMap<>();
    for (Product p : productRepository.findAllById(ids)) {
      byId.put(p.getId(), p);
    }
    for (Inventory inv : inventories) {
      if (inv == null || !StringUtils.hasText(inv.getProductId())) {
        continue;
      }
      Product product = byId.get(inv.getProductId());
      if (product != null) {
        apply(inv, product);
      }
    }
  }

  private void apply(Inventory inventory, Product product) {
    inventory.setBarcode(product.getBarcode());
    inventory.setName(product.getName());
    inventory.setDescription(product.getDescription());
    inventory.setCompanyName(product.getCompanyName());
    inventory.setBusinessType(product.getBusinessType());
    inventory.setItemType(product.getItemType());
    inventory.setItemTypeDegree(product.getItemTypeDegree());
    inventory.setBaseUnit(product.getBaseUnit());
    inventory.setUnitConversions(product.getUnitConversions());
    inventory.setHsn(product.getHsn());
  }
}
