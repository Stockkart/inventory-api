package com.inventory.documentservice.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.documentservice.api.MisKpi;
import com.inventory.documentservice.api.MisSheet;
import com.inventory.documentservice.api.MisTabularDocument;
import com.inventory.documentservice.api.PayslipDocument;
import com.inventory.documentservice.rest.dto.hr.PayslipDocumentRequest;
import com.inventory.documentservice.rest.dto.mis.MisTabularDocumentRequest;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The api records are copied into the renderers' own request classes. These tests check that the
 * copy is complete and exact: every field of the request class has a record component of the
 * same name, and every value (nested lists included) arrives unchanged.
 */
class ApiMappingTest {

  @Test
  void misDocumentIsCopiedFieldForField() {
    MisTabularDocument doc =
        MisTabularDocument.builder()
            .title("title")
            .shopName("shop")
            .periodLabel("period")
            .generatedAtLabel("generated")
            .kpis(List.of(new MisKpi("k1", "v1"), new MisKpi("k2", "v2")))
            .columns(List.of("c1", "c2"))
            .rows(List.of(List.of("a", "b"), List.of("c", "d")))
            .detailSheetTitle("detail")
            .secondarySheetTitle("secondary")
            .secondaryColumns(List.of("s1"))
            .secondaryRows(List.of(List.of("s")))
            .extraSheets(
                List.of(new MisSheet("extra", List.of("e1"), List.of(List.of("x", "y")))))
            .build();

    MisTabularDocumentRequest request = MisDocumentsImpl.toRequest(doc);

    assertSameData(doc, request);
  }

  @Test
  void misDocumentKeepsNullsAsNulls() {
    MisTabularDocumentRequest request =
        MisDocumentsImpl.toRequest(MisTabularDocument.builder().title("t").build());

    assertEquals("t", request.getTitle());
    assertNull(request.getKpis());
    assertNull(request.getRows());
    assertNull(request.getExtraSheets());
    assertNull(MisDocumentsImpl.toRequest(null));
  }

  @Test
  void payslipIsCopiedFieldForField() {
    PayslipDocument doc =
        PayslipDocument.builder()
            .shopName("shop")
            .shopAddress("address")
            .employeeName("name")
            .employeeCode("code")
            .designation("designation")
            .periodLabel("period")
            .dayRows(List.of(new PayslipDocument.Row("Present", "24")))
            .earnings(List.of(new PayslipDocument.Item("Basic", new BigDecimal("100.50"))))
            .deductions(List.of(new PayslipDocument.Item("Advance", new BigDecimal("20"))))
            .grossPay(new BigDecimal("1"))
            .totalDeductions(new BigDecimal("2"))
            .netBeforeRounding(new BigDecimal("3"))
            .rounding(new BigDecimal("0.40"))
            .netPay(new BigDecimal("5"))
            .netPayInWords("words")
            .advanceBalance(new BigDecimal("6"))
            .paymentNote("note")
            .generatedAtLabel("generated")
            .build();

    PayslipDocumentRequest request = PayslipDocumentsImpl.toRequest(doc);

    assertSameData(doc, request);
  }

  @Test
  void payslipToStringLeavesAmountsOut() {
    PayslipDocument doc =
        PayslipDocument.builder()
            .employeeName("Asha")
            .earnings(List.of(new PayslipDocument.Item("Basic", new BigDecimal("98765.43"))))
            .netPay(new BigDecimal("12345.67"))
            .netPayInWords("Twelve Thousand")
            .build();

    String text = doc.toString();

    assertTrue(text.contains("Asha"), text);
    assertTrue(!text.contains("98765") && !text.contains("12345") && !text.contains("Twelve"),
        text);
    assertTrue(!doc.earnings().get(0).toString().contains("98765"));
  }

  /**
   * Walks the record and the copied bean together: same field names, equal plain values, lists of
   * the same length compared item by item, nested records compared the same way.
   */
  private static void assertSameData(Object record, Object bean) {
    if (record == null) {
      assertNull(bean);
      return;
    }
    assertNotNull(bean);
    if (record instanceof List<?> recordList) {
      List<?> beanList = (List<?>) bean;
      assertEquals(recordList.size(), beanList.size());
      for (int i = 0; i < recordList.size(); i++) {
        assertSameData(recordList.get(i), beanList.get(i));
      }
      return;
    }
    if (!record.getClass().isRecord()) {
      assertEquals(record, bean);
      return;
    }
    RecordComponent[] components = record.getClass().getRecordComponents();
    Set<String> componentNames =
        Arrays.stream(components)
            .map(RecordComponent::getName)
            .collect(Collectors.toCollection(TreeSet::new));
    Set<String> beanFields =
        Arrays.stream(bean.getClass().getDeclaredFields())
            .filter(f -> !Modifier.isStatic(f.getModifiers()))
            .map(Field::getName)
            .collect(Collectors.toCollection(TreeSet::new));
    assertEquals(beanFields, componentNames, bean.getClass().getSimpleName() + " fields");
    for (RecordComponent c : components) {
      try {
        Object value = c.getAccessor().invoke(record);
        assertNotNull(value, c.getName() + " should be set in the test data");
        Field f = bean.getClass().getDeclaredField(c.getName());
        f.setAccessible(true);
        assertSameData(value, f.get(bean));
      } catch (ReflectiveOperationException e) {
        throw new AssertionError(c.getName(), e);
      }
    }
  }
}
