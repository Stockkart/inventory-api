package com.inventory.hr.service;

import static com.inventory.hr.HrTestFixtures.SHOP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.hr.domain.model.HrCounter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@ExtendWith(MockitoExtension.class)
class HrCounterServiceTest {

  @Mock private MongoTemplate mongoTemplate;
  @InjectMocks private HrCounterService service;

  @Test
  void codesArePaddedToThreeDigits() {
    assertEquals("EMP-001", HrCounterService.formatEmployeeCode(1));
    assertEquals("EMP-042", HrCounterService.formatEmployeeCode(42));
    assertEquals("EMP-1000", HrCounterService.formatEmployeeCode(1000));
  }

  @Test
  void theNextCodeIsOneAtomicIncrementWithUpsert() {
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
        any(FindAndModifyOptions.class), eq(HrCounter.class)))
        .thenReturn(new HrCounter(SHOP + ":EMPLOYEE_CODE", SHOP, 7L));

    assertEquals("EMP-007", service.nextEmployeeCode(SHOP));

    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
    ArgumentCaptor<FindAndModifyOptions> options =
        ArgumentCaptor.forClass(FindAndModifyOptions.class);
    verify(mongoTemplate).findAndModify(query.capture(), update.capture(), options.capture(),
        eq(HrCounter.class));
    assertEquals(SHOP + ":EMPLOYEE_CODE", query.getValue().getQueryObject().get("_id"));
    assertEquals(true, update.getValue().getUpdateObject().containsKey("$inc"));
    assertEquals(true, options.getValue().isUpsert());
    assertEquals(true, options.getValue().isReturnNew());
  }
}
