package com.inventory.product.mapper;

import com.inventory.notifications.rest.dto.request.CustomReminderRequest;
import com.inventory.ocr.dto.ParsedInventoryItem;
import com.inventory.ocr.dto.ParsedReminderDto;
import com.inventory.product.domain.model.ParsedInventoryResult;
import com.inventory.product.rest.dto.request.CreateInventoryItemRequest;
import com.inventory.product.rest.dto.response.ParsedInventoryListResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
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

  @Mapping(target = "businessType", expression = "java(mapBusinessType(parsedItem.getBusinessType()))")
  @Mapping(target = "thresholdCount", expression = "java(mapThresholdCount(parsedItem.getThresholdCount()))")
  @Mapping(target = "expiryDate", expression = "java(parseInstant(parsedItem.getExpiryDate()))")
  @Mapping(target = "reminderAt", expression = "java(parseInstant(parsedItem.getReminderAt()))")
  @Mapping(target = "customReminders", expression = "java(mapCustomReminders(parsedItem.getCustomReminders()))")
  CreateInventoryItemRequest toCreateInventoryItemRequest(ParsedInventoryItem parsedItem);

  default List<CreateInventoryItemRequest> toCreateInventoryItemRequestList(List<ParsedInventoryItem> parsedItems) {
    if (parsedItems == null) {
      return Collections.emptyList();
    }
    List<CreateInventoryItemRequest> requests = new ArrayList<>();
    for (ParsedInventoryItem item : parsedItems) {
      try {
        requests.add(toCreateInventoryItemRequest(item));
      } catch (Exception e) {
        LOG.error("Error converting ParsedInventoryItem to CreateInventoryItemRequest: {}", e.getMessage(), e);
      }
    }
    return requests;
  }

  default String mapBusinessType(String businessType) {
    return businessType != null ? businessType : "PHARMACEUTICAL";
  }

  default Integer mapThresholdCount(Integer thresholdCount) {
    return thresholdCount != null ? thresholdCount : 10;
  }

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

  default List<CustomReminderRequest> mapCustomReminders(List<ParsedReminderDto> parsedReminders) {
    if (parsedReminders == null || parsedReminders.isEmpty()) {
      return null;
    }
    List<CustomReminderRequest> reminders = new ArrayList<>();
    for (ParsedReminderDto r : parsedReminders) {
      Instant at = parseInstant(r.getReminderAt());
      Instant end = parseInstant(r.getEndDate());
      if (at != null || end != null) {
        CustomReminderRequest cr = new CustomReminderRequest();
        cr.setReminderAt(at);
        cr.setEndDate(end);
        cr.setNotes(r.getNotes());
        reminders.add(cr);
      }
    }
    return reminders.isEmpty() ? null : reminders;
  }
}
