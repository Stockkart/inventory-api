package com.inventory.pricing.domain.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inventory.pricing.domain.model.Pricing;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.ExecutableFindOperation;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

class PricingRepositoryImplTest {

  private static final String SHOP_ID = "shop-1";

  private MongoTemplate mongoTemplate;
  private ExecutableFindOperation.ExecutableFind<Pricing> find;
  private ExecutableFindOperation.TerminatingDistinct<Object> distinct;
  private ExecutableFindOperation.TerminatingDistinct<Object> matching;
  private ExecutableFindOperation.TerminatingDistinct<String> typed;
  private PricingRepositoryImpl repository;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    mongoTemplate = mock(MongoTemplate.class);
    find = mock(ExecutableFindOperation.ExecutableFind.class);
    distinct = mock(ExecutableFindOperation.TerminatingDistinct.class);
    matching = mock(ExecutableFindOperation.TerminatingDistinct.class);
    typed = mock(ExecutableFindOperation.TerminatingDistinct.class);

    when(mongoTemplate.query(Pricing.class)).thenReturn(find);
    when(find.distinct("rates.name")).thenReturn(distinct);
    when(distinct.matching(any(Query.class))).thenReturn(matching);
    when(matching.as(String.class)).thenReturn(typed);

    repository = new PricingRepositoryImpl(mongoTemplate);
  }

  private void stubDistinctResult(String... names) {
    when(typed.all()).thenReturn(Arrays.asList(names));
  }

  @Test
  void sortsNamesCaseInsensitively() {
    stubDistinctResult("rate-b", "Rate-A", "RATE-C", "rate-a2");

    List<String> names = repository.findDistinctRateNamesByShopId(SHOP_ID);

    assertThat(names).containsExactly("Rate-A", "rate-a2", "rate-b", "RATE-C");
  }

  @Test
  void dropsNullAndBlankNamesAndDuplicates() {
    stubDistinctResult("Wholesale", null, "", "  ", "Wholesale", "Retail");

    List<String> names = repository.findDistinctRateNamesByShopId(SHOP_ID);

    assertThat(names).containsExactly("Retail", "Wholesale");
  }

  @Test
  void returnsEmptyListWhenShopHasNoRates() {
    stubDistinctResult();

    assertThat(repository.findDistinctRateNamesByShopId(SHOP_ID)).isEmpty();
  }

  @Test
  void scopesDistinctQueryToShopId() {
    stubDistinctResult("Rate-A");
    ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);

    repository.findDistinctRateNamesByShopId(SHOP_ID);

    org.mockito.Mockito.verify(distinct).matching(queryCaptor.capture());
    assertThat(queryCaptor.getValue().getQueryObject().get("shopId")).isEqualTo(SHOP_ID);
    org.mockito.Mockito.verify(find).distinct(eq("rates.name"));
  }
}
