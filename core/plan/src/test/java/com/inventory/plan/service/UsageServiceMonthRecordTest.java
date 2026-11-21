package com.inventory.plan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.plan.domain.model.Usage;
import com.inventory.plan.domain.repository.UsageRepository;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.util.ReflectionTestUtils;

/** A month's record is created by one upsert (never find-then-save), and OCR is counted the same way. */
class UsageServiceMonthRecordTest {

  private MongoTemplate template;
  private UsageRepository usageRepository;
  private UsageService service;

  @BeforeEach
  void setUp() {
    template = mock(MongoTemplate.class);
    usageRepository = mock(UsageRepository.class);
    service = new UsageService();
    ReflectionTestUtils.setField(service, "mongoTemplate", template);
    ReflectionTestUtils.setField(service, "usageRepository", usageRepository);
    when(template.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Usage.class)))
        .thenReturn(new Usage());
  }

  private Update capturedUpdate() {
    ArgumentCaptor<Update> u = ArgumentCaptor.forClass(Update.class);
    ArgumentCaptor<FindAndModifyOptions> o = ArgumentCaptor.forClass(FindAndModifyOptions.class);
    verify(template).findAndModify(any(Query.class), u.capture(), o.capture(), eq(Usage.class));
    assertThat(o.getValue().isUpsert()).isTrue();
    return u.getValue();
  }

  @Test
  void aMissingMonthIsUpsertedWithZeroCounts() {
    when(usageRepository.findByShopIdAndMonth(eq("shop-1"), any())).thenReturn(Optional.empty());

    service.getOrCreateCurrentMonthUsage("shop-1");

    Document onInsert = (Document) capturedUpdate().getUpdateObject().get("$setOnInsert");
    assertThat(onInsert).containsEntry("shopId", "shop-1").containsEntry("billCountUsed", 0)
        .containsEntry("ocrUsed", 0).containsKey("billingAmountUsed");
  }

  @Test
  void anOcrScanIsOneUpsertIncrement() {
    service.recordOcrUsage("shop-1");

    Update update = capturedUpdate();
    assertThat(((Document) update.getUpdateObject().get("$inc")).get("ocrUsed")).isEqualTo(1);
    assertThat((Document) update.getUpdateObject().get("$setOnInsert")).doesNotContainKey("ocrUsed");
  }
}
