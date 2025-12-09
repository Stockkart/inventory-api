package com.inventory.user.rest.mapper;

import com.inventory.user.domain.model.JoinRequest;
import com.inventory.user.domain.model.JoinRequestStatus;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.rest.dto.joinrequest.AcceptJoinRequestResponse;
import com.inventory.user.rest.dto.joinrequest.JoinRequestDto;
import com.inventory.user.rest.dto.joinrequest.JoinRequestListResponse;
import com.inventory.user.rest.dto.joinrequest.ProcessJoinRequestResponse;
import com.inventory.user.rest.dto.joinrequest.SendJoinRequestRequest;
import com.inventory.user.rest.dto.joinrequest.SendJoinRequestResponse;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;

import java.time.Instant;
import java.util.List;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface JoinRequestMapper {

  // MongoDB will auto-generate the requestId as ObjectId
  @Mapping(target = "requestId", ignore = true)
  @Mapping(target = "shopId", ignore = true) // Will be set manually from owner's shopId
  @Mapping(target = "shopName", ignore = true)
  @Mapping(target = "userId", source = "user.userId")
  @Mapping(target = "userEmail", source = "user.email")
  @Mapping(target = "userName", source = "user.name")
  @Mapping(target = "requestedRole", ignore = true)
  @Mapping(target = "status", expression = "java(com.inventory.user.domain.model.JoinRequestStatus.PENDING)")
  @Mapping(target = "message", source = "request.message")
  @Mapping(target = "createdAt", expression = "java(java.time.Instant.now())")
  @Mapping(target = "reviewedAt", ignore = true)
  @Mapping(target = "reviewedBy", ignore = true)
  JoinRequest toEntity(SendJoinRequestRequest request, UserAccount user);

  @AfterMapping
  default void setRequestedRole(@MappingTarget JoinRequest joinRequest, SendJoinRequestRequest request, UserAccount user) {
    if (request.getRole() != null) {
      joinRequest.setRequestedRole(request.getRole().name());
    }
  }

  @Mapping(target = "requestId", source = "requestId")
  @Mapping(target = "shopId", source = "shopId")
  @Mapping(target = "shopName", source = "shopName")
  @Mapping(target = "status", source = "status")
  @Mapping(target = "message", source = "message")
  @Mapping(target = "createdAt", source = "createdAt")
  SendJoinRequestResponse toResponse(JoinRequest joinRequest);

  default void setShopName(@MappingTarget JoinRequest joinRequest, String shopName) {
    if (shopName != null) {
      joinRequest.setShopName(shopName);
    }
  }

  default void setShopId(@MappingTarget JoinRequest joinRequest, String shopId) {
    if (shopId != null) {
      joinRequest.setShopId(shopId);
    }
  }

  @Mapping(target = "requestId", source = "requestId")
  @Mapping(target = "shopId", source = "shopId")
  @Mapping(target = "shopName", source = "shopName")
  @Mapping(target = "userId", source = "userId")
  @Mapping(target = "userEmail", source = "userEmail")
  @Mapping(target = "userName", source = "userName")
  @Mapping(target = "requestedRole", source = "requestedRole")
  @Mapping(target = "status", source = "status")
  @Mapping(target = "message", source = "message")
  @Mapping(target = "createdAt", source = "createdAt")
  @Mapping(target = "reviewedAt", source = "reviewedAt")
  @Mapping(target = "reviewedBy", source = "reviewedBy")
  JoinRequestDto toDto(JoinRequest joinRequest);

  @AfterMapping
  default void setStatusString(@MappingTarget JoinRequestDto dto, JoinRequest joinRequest) {
    if (joinRequest.getStatus() != null) {
      dto.setStatus(joinRequest.getStatus().name());
    }
  }

  default JoinRequestListResponse toListResponse(List<JoinRequestDto> data) {
    return new JoinRequestListResponse(data);
  }

  @Mapping(target = "requestId", source = "joinRequest.requestId")
  @Mapping(target = "shopId", source = "joinRequest.shopId")
  @Mapping(target = "shopName", source = "joinRequest.shopName")
  @Mapping(target = "userId", source = "user.userId")
  @Mapping(target = "userEmail", source = "user.email")
  @Mapping(target = "userName", source = "user.name")
  @Mapping(target = "reviewedAt", source = "joinRequest.reviewedAt")
  @Mapping(target = "message", ignore = true)
  AcceptJoinRequestResponse toAcceptResponse(JoinRequest joinRequest, UserAccount user);

  @AfterMapping
  default void setAcceptResponseMessage(@MappingTarget AcceptJoinRequestResponse response, JoinRequest joinRequest, UserAccount user) {
    response.setMessage("Join request accepted successfully");
  }

  default ProcessJoinRequestResponse toProcessResponse(JoinRequest joinRequest, UserAccount user, String status, String message) {
    ProcessJoinRequestResponse response = new ProcessJoinRequestResponse();
    response.setRequestId(joinRequest.getRequestId());
    response.setShopId(joinRequest.getShopId());
    response.setShopName(joinRequest.getShopName());
    response.setStatus(status);
    response.setReviewedAt(joinRequest.getReviewedAt());
    response.setMessage(message);

    if (user != null) {
      response.setUserId(user.getUserId());
      response.setUserEmail(user.getEmail());
      response.setUserName(user.getName());
    } else {
      response.setUserId(joinRequest.getUserId());
      response.setUserEmail(joinRequest.getUserEmail());
      response.setUserName(joinRequest.getUserName());
    }

    return response;
  }

  /**
   * Updates join request status to APPROVED and updates user's shop with requested role
   */
  default void updateJoinRequestAndUser(JoinRequest joinRequest, UserAccount user, String reviewerId) {
    joinRequest.setStatus(JoinRequestStatus.APPROVED);
    joinRequest.setReviewedAt(Instant.now());
    joinRequest.setReviewedBy(reviewerId);

    user.setShopId(joinRequest.getShopId());
    // Use the requested role, default to CASHIER if not specified
    if (joinRequest.getRequestedRole() != null) {
      try {
        user.setRole(com.inventory.user.domain.model.UserRole.valueOf(joinRequest.getRequestedRole()));
      } catch (IllegalArgumentException e) {
        // If invalid role, default to CASHIER
        user.setRole(com.inventory.user.domain.model.UserRole.CASHIER);
      }
    } else {
      user.setRole(com.inventory.user.domain.model.UserRole.CASHIER);
    }
    user.setUpdatedAt(Instant.now());
  }

  /**
   * Updates join request status to REJECTED
   */
  default void rejectJoinRequest(JoinRequest joinRequest, String reviewerId) {
    joinRequest.setStatus(JoinRequestStatus.REJECTED);
    joinRequest.setReviewedAt(Instant.now());
    joinRequest.setReviewedBy(reviewerId);
  }
}

