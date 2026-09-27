package com.inventory.plan.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventory.common.entitlement.PlanFeature;
import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.repository.PlanRepository;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.mongodb.core.MongoTemplate;

@ExtendWith(MockitoExtension.class)
class PlanCatalogueSeederTest {

  @Mock
  private PlanRepository planRepository;

  @Mock
  private MongoTemplate mongoTemplate;

  @Spy
  private ObjectMapper objectMapper = new ObjectMapper();

  @Spy
  private ResourceLoader resourceLoader = new DefaultResourceLoader();

  @InjectMocks
  private PlanCatalogueSeeder seeder;

  @Test
  void classpathSeedMatchesThePricingMatrix() {
    Map<String, Plan> byCode = new HashMap<>();
    seeder.loadSeeds().forEach(p -> byCode.put(p.getCode(), p));

    assertThat(byCode).containsOnlyKeys("STARTER", "PROFESSIONAL", "ENTERPRISE");
    assertThat(byCode.get("STARTER").getArcPrice()).isEqualByComparingTo("6999");
    assertThat(byCode.get("PROFESSIONAL").getArcPrice()).isEqualByComparingTo("9999");
    assertThat(byCode.get("ENTERPRISE").getArcPrice()).isEqualByComparingTo("12999");
    assertThat(byCode.get("STARTER").getOcrLimit()).isEqualTo(100);
    assertThat(byCode.get("PROFESSIONAL").getOcrLimit()).isEqualTo(500);
    assertThat(byCode.get("ENTERPRISE").getOcrLimit()).isEqualTo(2000);
    assertThat(byCode.get("STARTER").getFeatures()).isEmpty();
    assertThat(byCode.get("PROFESSIONAL").getFeatures())
        .containsExactlyInAnyOrder(PlanFeature.CREDIT_BALANCE, PlanFeature.ACCOUNTING,
            PlanFeature.BARCODE_GENERATOR, PlanFeature.LOW_STOCK_NOTIFICATION);
    assertThat(byCode.get("ENTERPRISE").getFeatures()).containsExactlyInAnyOrder(PlanFeature.values());
    assertThat(byCode.get("PROFESSIONAL").getBadge()).isEqualTo("MOST_POPULAR");
  }

  @Test
  void insertsMissingTiersActiveAndLinksThemInDisplayOrder() {
    AtomicInteger ids = new AtomicInteger();
    when(planRepository.findByCode(any())).thenReturn(Optional.empty());
    when(planRepository.save(any(Plan.class))).thenAnswer(inv -> {
      Plan p = inv.getArgument(0);
      if (p.getId() == null) {
        p.setId("id-" + ids.incrementAndGet());
      }
      return p;
    });

    List<Plan> tiers = seeder.seed(seeder.loadSeeds());

    assertThat(tiers).extracting(Plan::getCode).containsExactly("STARTER", "PROFESSIONAL", "ENTERPRISE");
    assertThat(tiers).allMatch(p -> Boolean.TRUE.equals(p.getActive()));
    assertThat(tiers.get(0).getLinkedId()).isEqualTo(tiers.get(1).getId());
    assertThat(tiers.get(1).getLinkedId()).isEqualTo(tiers.get(2).getId());
    assertThat(tiers.get(2).getLinkedId()).isNull();
  }

  @Test
  void neverOverwritesAnExistingTier() {
    Plan edited = seedPlan("STARTER", 1, "5999");
    edited.setId("existing");
    edited.setLinkedId("pro");
    when(planRepository.findByCode("STARTER")).thenReturn(Optional.of(edited));

    List<Plan> tiers = seeder.seed(List.of(seedPlan("STARTER", 1, "6999")));

    assertThat(tiers.get(0).getArcPrice()).isEqualByComparingTo("5999");
    verify(planRepository, never()).save(any(Plan.class));
  }

  @Test
  void deactivatesOnlyActiveLegacyRows() {
    Plan base = legacy("Base", null);
    Plan silver = legacy("Silver", true);
    Plan alreadyOff = legacy("Old", false);
    when(planRepository.findByCodeIsNull()).thenReturn(List.of(base, silver, alreadyOff));

    assertThat(seeder.deactivateLegacyPlans()).isEqualTo(2);
    assertThat(base.getActive()).isFalse();
    assertThat(silver.getActive()).isFalse();
    verify(planRepository, times(2)).save(any(Plan.class));
  }

  @Test
  void rejectsSeedWithoutArcPrice() {
    Plan broken = seedPlan("STARTER", 1, null);

    assertThatThrownBy(() -> seeder.seed(List.of(broken)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("arcPrice");
  }

  @Test
  void rejectsDuplicateCodes() {
    assertThatThrownBy(() -> seeder.seed(List.of(seedPlan("STARTER", 1, "1"), seedPlan("STARTER", 2, "2"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Duplicate");
  }

  private static Plan seedPlan(String code, int order, String arcPrice) {
    Plan p = new Plan();
    p.setCode(code);
    p.setPlanName(code);
    p.setDisplayOrder(order);
    p.setArcPrice(arcPrice == null ? null : new BigDecimal(arcPrice));
    return p;
  }

  private static Plan legacy(String name, Boolean active) {
    Plan p = new Plan();
    p.setPlanName(name);
    p.setActive(active);
    return p;
  }
}
