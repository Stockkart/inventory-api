package com.inventory.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.product.domain.model.Shop;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/** Visiting every shop's plan fields pages by _id with a projection, never by skip. */
@ExtendWith(MockitoExtension.class)
class ShopServicePlanInfoPagingTest {

  @Mock private MongoTemplate mongoTemplate;
  @InjectMocks private ShopService shopService;

  private static List<Shop> shops(int from, int count) {
    return IntStream.range(from, from + count).mapToObj(i -> {
      Shop shop = new Shop();
      shop.setShopId(String.format("%024d", i));
      return shop;
    }).toList();
  }

  @Test
  void pagesAfterTheLastIdUntilAShortPage() {
    when(mongoTemplate.find(any(Query.class), eq(Shop.class)))
        .thenReturn(shops(0, ShopService.PLAN_INFO_PAGE), shops(ShopService.PLAN_INFO_PAGE, 3));
    List<String> seen = new ArrayList<>();

    shopService.forEachShopPlanInfo(info -> seen.add(info.shopId()));

    assertThat(seen).hasSize(ShopService.PLAN_INFO_PAGE + 3);
    ArgumentCaptor<Query> queries = ArgumentCaptor.forClass(Query.class);
    verify(mongoTemplate, times(2)).find(queries.capture(), eq(Shop.class));
    Query second = queries.getAllValues().get(1);
    assertThat(second.getQueryObject().toString()).contains("$gt").contains(String.format("%024d", ShopService.PLAN_INFO_PAGE - 1));
    assertThat(second.getSkip()).isZero();
    assertThat(second.getFieldsObject()).containsKeys("planId", "planExpiryDate");
  }
}
