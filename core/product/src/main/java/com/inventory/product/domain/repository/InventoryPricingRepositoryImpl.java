package com.inventory.product.domain.repository;

import com.inventory.product.domain.model.Inventory;
import com.inventory.product.rest.dto.inventory.InventoryPricingDto;
import com.inventory.product.service.InventoryPricingAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class InventoryPricingRepositoryImpl implements InventoryPricingRepository {

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private InventoryPricingAdapter pricingAdapter;

  @Override
  public List<Inventory> findByShopId(String shopId, Pageable pageable) {

    Query query = new Query()
      .addCriteria(Criteria.where("shopId").is(shopId))
      .with(pageable);

    List<Inventory> inventories =
      mongoTemplate.find(query, Inventory.class);

    enrichPricing(inventories);

    return inventories;
  }


  @Override
  public List<Inventory> searchByShopIdAndQuery(String shopId, String search) {

    Query query = new Query().addCriteria(
      new Criteria().andOperator(
        Criteria.where("shopId").is(shopId),
        new Criteria().orOperator(
          Criteria.where("barcode").regex(search, "i"),
          Criteria.where("name").regex(search, "i"),
          Criteria.where("companyName").regex(search, "i")
        )
      )
    );

    List<Inventory> inventories =
      mongoTemplate.find(query, Inventory.class);

    enrichPricing(inventories);

    return inventories;
  }

  private void enrichPricing(List<Inventory> inventories) {

    List<String> pricingIds = inventories.stream()
      .map(Inventory::getPricingId)
      .filter(Objects::nonNull)
      .distinct()
      .toList();

    if (pricingIds.isEmpty()) return;

    Map<String, InventoryPricingDto> pricingMap =
      pricingAdapter.getPricingBulk(pricingIds);

    inventories.forEach(inv -> {
      if (inv.getPricingId() != null) {
        InventoryPricingDto pricing = pricingMap.get(inv.getPricingId());

        if (pricing != null) {
          inv.setMaximumRetailPrice(pricing.getMaximumRetailPrice());
          inv.setCostPrice(pricing.getCostPrice());
          inv.setSellingPrice(pricing.getSellingPrice());
          inv.setAdditionalDiscount(pricing.getAdditionalDiscount());
          inv.setSgst(pricing.getSgst());
          inv.setCgst(pricing.getCgst());
        }
      }
    });
  }
}

