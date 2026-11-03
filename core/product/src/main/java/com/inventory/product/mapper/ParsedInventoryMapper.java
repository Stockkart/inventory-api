package com.inventory.product.mapper;

import com.inventory.product.rest.dto.request.CustomReminderRequest;
import com.inventory.ocr.api.ParsedInvoiceItem;
import com.inventory.ocr.api.ParsedReminder;
import com.inventory.product.domain.model.ParsedInventoryResult;
import com.inventory.product.domain.model.UnitConversion;
import com.inventory.product.domain.model.enums.SchemeType;
import com.inventory.product.rest.dto.request.CreateInventoryItemRequest;
import org.springframework.util.StringUtils;
import com.inventory.product.rest.dto.response.ParsedInventoryListResponse;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MapStruct mapper for parsed inventory conversions.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ParsedInventoryMapper {

  Logger LOG = LoggerFactory.getLogger(ParsedInventoryMapper.class);

  @Mapping(target = "items", source = "parsedItems")
  @Mapping(target = "totalItems", expression = "java(result.getParsedItems() != null ? result.getParsedItems().size() : 0)")
  ParsedInventoryListResponse toParsedInventoryListResponse(ParsedInventoryResult result);

  default ParsedInventoryListResponse toParsedInventoryListResponse(List<CreateInventoryItemRequest> items) {
    return toParsedInventoryListResponse(items, null);
  }

  default ParsedInventoryListResponse toParsedInventoryListResponse(
      List<CreateInventoryItemRequest> items,
      com.inventory.product.rest.dto.response.ParsedVendorInvoiceDto vendorPurchaseInvoice) {
    ParsedInventoryListResponse response = new ParsedInventoryListResponse();
    response.setItems(items);
    response.setTotalItems(items != null ? items.size() : 0);
    response.setVendorPurchaseInvoice(vendorPurchaseInvoice);
    return response;
  }

  default ParsedInventoryResult toParsedInventoryResult(
      String uploadTokenId,
      String userId,
      String shopId,
      List<CreateInventoryItemRequest> parsedItems) {
    ParsedInventoryResult result = new ParsedInventoryResult();
    result.setUploadTokenId(uploadTokenId);
    result.setUserId(userId);
    result.setShopId(shopId);
    result.setParsedItems(parsedItems);
    result.setCreatedAt(Instant.now());
    return result;
  }

  @Mapping(target = "businessType", source = "businessType", qualifiedByName = "mapBusinessType")
  @Mapping(target = "thresholdCount", source = "thresholdCount", qualifiedByName = "mapThresholdCount")
  @Mapping(target = "expiryDate", source = "expiryDate", qualifiedByName = "parseInstant")
  @Mapping(target = "reminderAt", source = "reminderAt", qualifiedByName = "parseInstant")
  @Mapping(target = "purchaseDate", source = "purchaseDate", qualifiedByName = "parseInstant")
  @Mapping(target = "customReminders", source = "customReminders", qualifiedByName = "mapCustomReminders")
  CreateInventoryItemRequest toCreateInventoryItemRequest(ParsedInvoiceItem parsedItem);

  @AfterMapping
  default void enrichPackagingFields(
      @MappingTarget CreateInventoryItemRequest target,
      ParsedInvoiceItem source) {
    if (StringUtils.hasText(source.baseUnit())) {
      target.setBaseUnit(source.baseUnit().trim().toUpperCase());
    }
    if (source.unitsPerPack() != null && source.unitsPerPack() > 1) {
      target.setUnitsPerPack(source.unitsPerPack());
      if (StringUtils.hasText(source.baseUnit())) {
        UnitConversion conversion = new UnitConversion();
        conversion.setUnit("PAC");
        conversion.setFactor(source.unitsPerPack());
        target.setUnitConversions(conversion);
      }
    }
  }

  @AfterMapping
  default void enrichSchemeFields(
      @MappingTarget CreateInventoryItemRequest target,
      ParsedInvoiceItem source) {
    if (source.schemePayFor() != null || source.schemeFree() != null) {
      target.setSchemeType(SchemeType.FIXED_UNITS);
      target.setSchemePayFor(source.schemePayFor());
      target.setSchemeFree(source.schemeFree());
      target.setScheme(null);
    } else if (source.scheme() != null) {
      target.setScheme(source.scheme());
    }
    if (source.purchaseSchemePayFor() != null || source.purchaseSchemeFree() != null) {
      target.setPurchaseSchemeType(SchemeType.FIXED_UNITS);
      target.setPurchaseSchemePayFor(source.purchaseSchemePayFor());
      target.setPurchaseSchemeFree(source.purchaseSchemeFree());
    }
  }

  default List<CreateInventoryItemRequest> toCreateInventoryItemRequestList(List<ParsedInvoiceItem> parsedItems) {
    if (parsedItems == null) {
      return Collections.emptyList();
    }
    List<CreateInventoryItemRequest> requests = new ArrayList<>();
    for (ParsedInvoiceItem item : parsedItems) {
      try {
        requests.add(toCreateInventoryItemRequest(item));
      } catch (Exception e) {
        LOG.error("Error converting ParsedInventoryItem to CreateInventoryItemRequest: {}", e.getMessage(), e);
      }
    }
    return requests;
  }

  @Named("mapBusinessType")
  default String mapBusinessType(String businessType) {
    return businessType != null ? businessType : "PHARMACEUTICAL";
  }

  @Named("mapThresholdCount")
  default Integer mapThresholdCount(Integer thresholdCount) {
    return thresholdCount != null ? thresholdCount : 10;
  }

  @Named("parseInstant")
  default Instant parseInstant(String value) {
    if (value == null || value.trim().isEmpty()) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (Exception e) {
      return null;
    }
  }

  @Named("mapCustomReminders")
  default List<CustomReminderRequest> mapCustomReminders(List<ParsedReminder> parsedReminders) {
    if (parsedReminders == null || parsedReminders.isEmpty()) {
      return null;
    }
    List<CustomReminderRequest> reminders = new ArrayList<>();
    for (ParsedReminder r : parsedReminders) {
      Instant at = parseInstant(r.reminderAt());
      Instant end = parseInstant(r.endDate());
      if (at != null || end != null) {
        CustomReminderRequest cr = new CustomReminderRequest();
        cr.setReminderAt(at);
        cr.setEndDate(end);
        cr.setNotes(r.notes());
        reminders.add(cr);
      }
    }
    return reminders.isEmpty() ? null : reminders;
  }
}
