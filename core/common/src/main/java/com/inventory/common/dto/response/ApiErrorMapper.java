package com.inventory.common.dto.response;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.util.Map;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ApiErrorMapper {

  @Mapping(target = "message", source = "message")
  @Mapping(target = "status", source = "status")
  @Mapping(target = "errors", source = "errors")
  ApiError toApiError(String message, int status, Map<String, String[]> errors);

  default ApiError toApiError(String message, int status) {
    ApiError error = new ApiError();
    error.setMessage(message);
    error.setStatus(status);
    error.setErrors(null);
    return error;
  }
}

