package com.inventory.pricing.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.inventory.pricing.domain.model.Pricing;
import com.inventory.pricing.domain.model.Rate;
import com.inventory.pricing.domain.model.Scheme;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Stored pricing records are copied into the api records. These tests check the copy is complete
 * and exact: same field names on both sides (nested rates and schemes included), every value
 * unchanged, nulls kept as nulls.
 */
class PricingLookupMappingTest {

  @Test
  void pricingIsCopiedFieldForField() {
    Rate rate =
        new Rate("Wholesale", new BigDecimal("90"), "sellingPrice", new BigDecimal("100"),
            new BigDecimal("10"), new Scheme("FIXED_UNITS", 10, 1, new BigDecimal("0")), true,
            "INCLUSIVE");
    Pricing p =
        new Pricing(
            "pr-1",
            "shop-1",
            new BigDecimal("120"),
            new BigDecimal("70"),
            new BigDecimal("63.6364"),
            Boolean.TRUE,
            new BigDecimal("95"),
            List.of(rate),
            "Wholesale",
            new BigDecimal("100"),
            new BigDecimal("2"),
            new BigDecimal("3"),
            new Scheme("PERCENTAGE", 1, 2, new BigDecimal("5")),
            new Scheme("FIXED_UNITS", 8, 2, new BigDecimal("1")),
            "6",
            "6",
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-02T00:00:00Z"));

    assertSameData(PricingLookupImpl.toView(p), p);
  }

  @Test
  void nullsStayNull() {
    Pricing p = new Pricing();
    p.setId("pr-2");

    var view = PricingLookupImpl.toView(p);

    assertEquals("pr-2", view.id());
    assertNull(view.rates());
    assertNull(view.purchaseScheme());
    assertNull(PricingLookupImpl.toView(null));
  }

  /** Same field names on both sides, every value set and equal, nested objects walked. */
  private static void assertSameData(Object record, Object bean) {
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
      } catch (ReflectiveOperationException ex) {
        throw new AssertionError(c.getName(), ex);
      }
    }
  }
}
