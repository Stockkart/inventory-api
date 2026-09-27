package com.inventory.plan.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventory.common.entitlement.PlanFeature;
import java.util.List;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

class PlanMappingTest {

  private MappingMongoConverter converter;

  @BeforeEach
  void setUp() {
    MongoCustomConversions conversions = new MongoCustomConversions(List.of());
    MongoMappingContext context = new MongoMappingContext();
    context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
    context.afterPropertiesSet();
    converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
    converter.setCustomConversions(conversions);
    converter.afterPropertiesSet();
  }

  @Test
  void readsRowWhoseFeaturesFieldHoldsDisplayObjects() {
    Document row = new Document("planName", "Starter")
        .append("kind", "PLAN")
        .append("features", List.of(new Document("key", "PRODUCTS_AND_SALES")
            .append("label", "Products & Sales")
            .append("availability", "INCLUDED")
            .append("sortOrder", 0)));

    Plan plan = converter.read(Plan.class, row);

    assertThat(plan.getPlanName()).isEqualTo("Starter");
    assertThat(plan.getFeatures()).isNull();
  }

  @Test
  void storesEntitlementsOutsideTheFeaturesField() {
    Plan plan = new Plan();
    plan.setCode("PROFESSIONAL");
    plan.setFeatures(Set.of(PlanFeature.ACCOUNTING));

    Document written = new Document();
    converter.write(plan, written);

    assertThat(written).doesNotContainKey("features");
    assertThat(written.getList("entitlements", String.class)).containsExactly("ACCOUNTING");
    assertThat(converter.read(Plan.class, written).getFeatures()).containsExactly(PlanFeature.ACCOUNTING);
  }
}
