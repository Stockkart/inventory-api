package com.inventory.plan.domain.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.domain.model.ReferralReward;
import com.inventory.plan.domain.model.ShopCreditEntry;
import com.inventory.plan.domain.model.VoucherRedemption;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.repository.query.parser.Part;
import org.springframework.data.repository.query.parser.PartTree;

/**
 * Spring Data Mongo builds a derived query as one criteria document, so naming the same field twice
 * (e.g. {@code PaidAtGreaterThanEqualAndPaidAtLessThan}) fails at runtime. Range queries need {@code @Query}.
 */
class DerivedQueryRangeTest {

  private static final Map<Class<?>, Class<?>> REPOSITORIES = Map.of(
      PlanPaymentOrderRepository.class, PlanPaymentOrder.class,
      VoucherRedemptionRepository.class, VoucherRedemption.class,
      ShopCreditEntryRepository.class, ShopCreditEntry.class,
      ReferralRewardRepository.class, ReferralReward.class);

  @Test
  void derivedQueriesNeverRepeatAField() {
    List<String> offenders = new ArrayList<>();
    REPOSITORIES.forEach((repository, domain) -> {
      for (Method method : repository.getDeclaredMethods()) {
        if (method.isAnnotationPresent(Query.class) || method.isDefault()) {
          continue;
        }
        for (PartTree.OrPart or : new PartTree(method.getName(), domain)) {
          Set<String> fields = new HashSet<>();
          for (Part part : or) {
            if (!fields.add(part.getProperty().toDotPath())) {
              offenders.add(repository.getSimpleName() + "." + method.getName());
            }
          }
        }
      }
    });
    assertThat(offenders).isEmpty();
  }
}
