package com.inventory.plan.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.inventory.plan.domain.model.AddOnVoucher;
import com.inventory.plan.domain.model.VoucherTarget;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.util.ReflectionTestUtils;

class VoucherTargetBackfillTest {

  private final MongoTemplate template = mock(MongoTemplate.class);
  private final VoucherTargetBackfill backfill = new VoucherTargetBackfill();

  VoucherTargetBackfillTest() {
    ReflectionTestUtils.setField(backfill, "mongoTemplate", template);
  }

  @Test
  void offByDefaultTouchesNothing() {
    backfill.run();

    verifyNoInteractions(template);
  }

  @Test
  void setsAddOnOnVouchersWithoutATarget() {
    backfill.enabled = true;
    when(template.count(any(Query.class), eq(AddOnVoucher.class))).thenReturn(3L, 0L);
    when(template.updateMulti(any(Query.class), any(Update.class), eq(AddOnVoucher.class)))
        .thenReturn(UpdateResult.acknowledged(3, 3L, null));

    backfill.run();

    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
    verify(template).updateMulti(query.capture(), update.capture(), eq(AddOnVoucher.class));
    assertThat(query.getValue().getQueryObject()).containsEntry("target", null);
    assertThat(((Document) update.getValue().getUpdateObject().get("$set")).get("target")).isEqualTo(VoucherTarget.ADD_ON);
  }

  @Test
  void aDryRunOnlyCounts() {
    backfill.enabled = true;
    backfill.dryRun = true;
    when(template.count(any(Query.class), eq(AddOnVoucher.class))).thenReturn(3L);

    backfill.run();

    verify(template, never()).updateMulti(any(Query.class), any(Update.class), eq(AddOnVoucher.class));
  }
}
