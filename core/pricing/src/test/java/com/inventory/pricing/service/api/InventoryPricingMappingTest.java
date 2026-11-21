package com.inventory.pricing.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.inventory.pricing.api.LotPricing;
import com.inventory.pricing.api.PricingCreate;
import com.inventory.pricing.api.PricingUpdate;
import com.inventory.pricing.api.RateLine;
import com.inventory.pricing.api.SchemeTerms;
import com.inventory.pricing.rest.dto.response.PricingReadDto;
import com.inventory.pricing.rest.dto.response.RateDto;
import com.inventory.pricing.rest.dto.response.SchemeDto;
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
 * Api records are copied into and out of pricing's own classes. These tests check every copy is
 * complete and exact: same field names on both sides, nested rates and schemes included, every
 * value unchanged, nulls kept as nulls.
 */
class InventoryPricingMappingTest {

  private static final SchemeTerms TERMS = new SchemeTerms("FIXED_UNITS", 10, 1, new BigDecimal("2"));
  private static final RateLine RATE =
      new RateLine("wholesale", new BigDecimal("90"), "priceToRetail", new BigDecimal("100"),
          new BigDecimal("10"), TERMS, Boolean.TRUE, "INCLUSIVE", "PTR 100.00 − 10%");

  @Test
  void readDtoIsCopiedFieldForField() {
    PricingReadDto dto =
        new PricingReadDto(
            new BigDecimal("120"),
            new BigDecimal("70"),
            new BigDecimal("63"),
            new BigDecimal("95"),
            List.of(new RateDto("wholesale", new BigDecimal("90"), "priceToRetail",
                new BigDecimal("100"), new BigDecimal("10"),
                new SchemeDto("FIXED_UNITS", 10, 1, new BigDecimal("2")), Boolean.TRUE,
                "INCLUSIVE", "PTR 100.00 − 10%")),
            "wholesale",
            new BigDecimal("100"),
            new BigDecimal("2"),
            new BigDecimal("3"),
            new SchemeDto("PERCENTAGE", 1, 2, new BigDecimal("5")),
            new SchemeDto("FIXED_UNITS", 8, 2, new BigDecimal("1")),
            "6",
            "6",
            Boolean.TRUE);

    LotPricing lot = InventoryPricingImpl.toLotPricing(dto);

    assertSameData(lot, dto);
    assertEquals(dto.getEffectivePrice(), lot.effectivePrice());
    assertEquals(dto.resolveEffectiveCostPrice(), lot.resolveEffectiveCostPrice());
    assertNull(InventoryPricingImpl.toLotPricing(null));
  }

  @Test
  void createIsCopiedFieldForField() {
    PricingCreate create =
        PricingCreate.builder()
            .shopId("shop")
            .verticalId("pharmacy")
            .maximumRetailPrice(new BigDecimal("120"))
            .costPrice(new BigDecimal("70"))
            .priceToRetail(new BigDecimal("95"))
            .sellingPrice(new BigDecimal("100"))
            .rates(List.of(RATE))
            .defaultRate("wholesale")
            .saleAdditionalDiscount(new BigDecimal("2"))
            .purchaseAdditionalDiscount(new BigDecimal("3"))
            .purchaseScheme(new SchemeTerms("PERCENTAGE", 1, 2, new BigDecimal("5")))
            .saleScheme(TERMS)
            .sgst("6")
            .cgst("6")
            .costPriceIncludesTax(Boolean.FALSE)
            .build();

    assertSameData(create, InventoryPricingImpl.toCommand(create));
    assertNull(InventoryPricingImpl.toCommand((PricingCreate) null));
  }

  @Test
  void updateIsCopiedFieldForField() {
    PricingUpdate update =
        PricingUpdate.builder()
            .verticalId("pharmacy")
            .maximumRetailPrice(new BigDecimal("120"))
            .costPrice(new BigDecimal("70"))
            .priceToRetail(new BigDecimal("95"))
            .sellingPrice(new BigDecimal("100"))
            .rates(List.of(RATE))
            .defaultRate("wholesale")
            .saleAdditionalDiscount(new BigDecimal("2"))
            .purchaseAdditionalDiscount(new BigDecimal("3"))
            .purchaseScheme(new SchemeTerms("PERCENTAGE", 1, 2, new BigDecimal("5")))
            .saleScheme(TERMS)
            .sgst("6")
            .cgst("6")
            .clearFields(Set.of("saleScheme"))
            .build();

    assertSameData(update, InventoryPricingImpl.toCommand(update));
    assertNull(InventoryPricingImpl.toCommand((PricingUpdate) null));
  }

  @Test
  void nullListsAndSchemesStayNull() {
    PricingUpdate empty = PricingUpdate.builder().build();

    var command = InventoryPricingImpl.toCommand(empty);

    assertNull(command.getRates());
    assertNull(command.getSaleScheme());
    assertNull(command.getClearFields());
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
