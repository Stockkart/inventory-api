package com.inventory.plan.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.plan.domain.model.Plan;
import com.mongodb.client.result.UpdateResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.util.ReflectionTestUtils;

/** Brochure limits reach rows seeded before they existed, without touching values an admin set. */
class PlanBrochureFieldsBackfillTest {

  private final MongoTemplate template = mock(MongoTemplate.class);
  private final PlanCatalogueSeeder seeder = mock(PlanCatalogueSeeder.class);
  private final PlanBrochureFieldsBackfill backfill = new PlanBrochureFieldsBackfill();

  PlanBrochureFieldsBackfillTest() {
    ReflectionTestUtils.setField(backfill, "mongoTemplate", template);
    ReflectionTestUtils.setField(backfill, "seeder", seeder);
    Plan starter = new Plan();
    starter.setCode("STARTER");
    starter.setRoleLimit(2);
    when(seeder.loadSeeds()).thenReturn(List.of(starter));
  }

  @Test
  void setsAFieldOnlyWhereTheRowNeverHadIt() {
    when(template.updateFirst(any(Query.class), any(Update.class), eq(Plan.class)))
        .thenReturn(UpdateResult.acknowledged(1, 1L, null));

    assertThat(backfill.run()).isEqualTo(1);

    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    verify(template).updateFirst(query.capture(), any(Update.class), eq(Plan.class));
    assertThat(query.getValue().getQueryObject().toString()).contains("STARTER").contains("roleLimit").contains("$exists");
  }

  @Test
  void dryRunOnlyCounts() {
    backfill.dryRun = true;
    when(template.count(any(Query.class), eq(Plan.class))).thenReturn(1L);

    assertThat(backfill.run()).isEqualTo(1);
    verify(template, never()).updateFirst(any(Query.class), any(Update.class), eq(Plan.class));
  }

  @Test
  void offByDefault() {
    backfill.runOnStartup();

    verify(seeder, never()).loadSeeds();
  }
}
