package com.inventory.product.mapper;

import com.inventory.product.domain.model.UploadToken;
import com.inventory.product.rest.dto.response.CreateUploadTokenResponse;
import com.inventory.product.rest.dto.response.TokenValidationResponse;
import com.inventory.product.rest.dto.response.UploadStatusResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.time.Duration;
import java.time.Instant;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface UploadTokenMapper {

  @Mapping(target = "token", source = "uploadToken.token")
  @Mapping(target = "uploadUrl", ignore = true) // Set in service
  @Mapping(target = "expiresInSeconds", ignore = true) // Calculated in service
  CreateUploadTokenResponse toCreateUploadTokenResponse(UploadToken uploadToken);

  default CreateUploadTokenResponse toCreateUploadTokenResponse(UploadToken uploadToken, String uploadUrl) {
    CreateUploadTokenResponse response = toCreateUploadTokenResponse(uploadToken);
    response.setUploadUrl(uploadUrl);
    response.setExpiresInSeconds(calculateExpiresInSeconds(uploadToken.getExpiresAt()));
    return response;
  }

  @Mapping(target = "token", source = "uploadToken.token")
  @Mapping(target = "status", source = "uploadToken.status")
  @Mapping(target = "expiresAt", source = "uploadToken.expiresAt")
  @Mapping(target = "errorMessage", ignore = true) // Set in service based on status
  TokenValidationResponse toTokenValidationResponse(UploadToken uploadToken);

  @Mapping(target = "token", source = "uploadToken.token")
  @Mapping(target = "status", source = "uploadToken.status")
  @Mapping(target = "parsedInventoryId", source = "uploadToken.parsedInventoryId")
  @Mapping(target = "errorMessage", ignore = true) // Set in service based on status
  UploadStatusResponse toUploadStatusResponse(UploadToken uploadToken);

  default long calculateExpiresInSeconds(Instant expiresAt) {
    if (expiresAt == null) {
      return 0;
    }
    return Duration.between(Instant.now(), expiresAt).getSeconds();
  }
}
