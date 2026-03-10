package com.inventory.user.mapper;

import com.inventory.user.domain.model.Invitation;
import com.inventory.user.domain.model.InvitationStatus;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.rest.dto.response.AcceptInvitationResponse;
import com.inventory.user.rest.dto.response.InvitationDto;
import com.inventory.user.rest.dto.response.InvitationListResponse;
import com.inventory.user.rest.dto.request.SendInvitationRequest;
import com.inventory.user.rest.dto.response.SendInvitationResponse;
import com.inventory.user.rest.dto.response.ShopUserDto;
import com.inventory.user.rest.dto.response.ShopUserListResponse;
import com.inventory.user.utils.constants.InvitationConstants;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;

import java.time.Instant;
import java.util.List;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface InvitationMapper {

  @Mapping(target = "invitationId", ignore = true)
  @Mapping(target = "shopId", source = "shopId")
  @Mapping(target = "inviterUserId", source = "inviterUserId")
  @Mapping(target = "inviteeUserId", ignore = true)
  @Mapping(target = "inviteeEmail", ignore = true)
  @Mapping(target = "role", source = "request.role")
  @Mapping(target = "status", expression = "java(com.inventory.user.domain.model.InvitationStatus.PENDING.name())")
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "expiresAt", ignore = true)
  @Mapping(target = "acceptedAt", ignore = true)
  @Mapping(target = "rejectedAt", ignore = true)
  Invitation toEntity(String shopId, String inviterUserId, SendInvitationRequest request);

  @AfterMapping
  default void setInvitationFields(@MappingTarget Invitation invitation, String shopId, String inviterUserId, SendInvitationRequest request) {
    setInvitationTimestampsAndId(invitation, request);
  }

  /**
   * Helper method to set invitation timestamps and normalize email.
   * MongoDB will auto-generate the invitationId as ObjectId.
   * This can be called explicitly if @AfterMapping doesn't work.
   */
  default void setInvitationTimestampsAndId(Invitation invitation, SendInvitationRequest request) {
    // MongoDB will auto-generate the invitationId as ObjectId

    // Set timestamps
    Instant currentTime = Instant.now();
    invitation.setCreatedAt(currentTime);
    invitation.setExpiresAt(currentTime.plusSeconds(InvitationConstants.INVITATION_EXPIRY_SECONDS));

    // Normalize email (lowercase and trim)
    if (request != null && request.getInviteeEmail() != null) {
      invitation.setInviteeEmail(request.getInviteeEmail().toLowerCase().trim());
    }
  }

  @Mapping(target = "invitationId", source = "invitation.invitationId")
  @Mapping(target = "shopId", source = "invitation.shopId")
  @Mapping(target = "inviteeEmail", source = "invitation.inviteeEmail")
  @Mapping(target = "role", source = "invitation.role")
  @Mapping(target = "status", source = "invitation.status")
  @Mapping(target = "createdAt", source = "invitation.createdAt")
  @Mapping(target = "expiresAt", source = "invitation.expiresAt")
  @Mapping(target = "message", ignore = true)
  SendInvitationResponse toSendResponse(Invitation invitation);

  @AfterMapping
  default void setSendResponseMessage(@MappingTarget SendInvitationResponse response, Invitation invitation) {
    response.setMessage("Invitation sent successfully");
  }

  @Mapping(target = "invitationId", source = "invitation.invitationId")
  @Mapping(target = "shopId", source = "invitation.shopId")
  @Mapping(target = "shopName", source = "shopName")
  @Mapping(target = "userId", source = "user.userId")
  @Mapping(target = "role", source = "invitation.role")
  @Mapping(target = "acceptedAt", source = "invitation.acceptedAt")
  @Mapping(target = "message", ignore = true)
  AcceptInvitationResponse toAcceptResponse(Invitation invitation, String shopName, UserAccount user);

  @AfterMapping
  default void setAcceptResponseMessage(@MappingTarget AcceptInvitationResponse response, Invitation invitation, String shopName, UserAccount user) {
    response.setMessage("Invitation accepted successfully");
  }

  @Mapping(target = "invitationId", source = "invitation.invitationId")
  @Mapping(target = "shopId", source = "invitation.shopId")
  @Mapping(target = "shopName", ignore = true)
  @Mapping(target = "inviterUserId", source = "invitation.inviterUserId")
  @Mapping(target = "inviterName", ignore = true)
  @Mapping(target = "inviteeUserId", source = "invitation.inviteeUserId")
  @Mapping(target = "inviteeEmail", source = "invitation.inviteeEmail")
  @Mapping(target = "inviteeName", ignore = true)
  @Mapping(target = "role", source = "invitation.role")
  @Mapping(target = "status", source = "invitation.status")
  @Mapping(target = "createdAt", source = "invitation.createdAt")
  @Mapping(target = "expiresAt", source = "invitation.expiresAt")
  @Mapping(target = "acceptedAt", source = "invitation.acceptedAt")
  @Mapping(target = "rejectedAt", source = "invitation.rejectedAt")
  InvitationDto toDto(Invitation invitation);

  // UserShopDto is now built manually in service to avoid Shop dependency

  @Mapping(target = "userId", source = "user.userId")
  @Mapping(target = "name", source = "user.name")
  @Mapping(target = "email", source = "user.email")
  @Mapping(target = "role", source = "user.role")
  @Mapping(target = "relationship", ignore = true)
  @Mapping(target = "active", source = "user.active")
  @Mapping(target = "joinedAt", ignore = true)
  ShopUserDto toShopUserDto(UserAccount user, String relationship, Instant joinedAt);

  /**
   * Sets inviteeUserId and shopName on the invitation entity
   */
  default void setInviteeAndShopName(Invitation invitation, String inviteeUserId, String shopName) {
    invitation.setInviteeUserId(inviteeUserId);
    invitation.setShopName(shopName);
  }

  /**
   * Updates invitation status to ACCEPTED. Does NOT update user's shopId/role here -
   * InvitationService handles membership add and optional active shop update.
   */
  default void updateInvitationStatus(Invitation invitation) {
    invitation.setStatus(InvitationStatus.ACCEPTED.name());
    invitation.setAcceptedAt(Instant.now());
  }

  /**
   * Enriches InvitationDto with shopName, inviterName, and inviteeName
   */
  default void enrichInvitationDto(InvitationDto dto, Invitation invitation,
                                   String shopName, UserAccount inviter, UserAccount invitee) {
    if (invitation.getShopName() != null) {
      dto.setShopName(invitation.getShopName());
    } else if (shopName != null) {
      dto.setShopName(shopName);
    }

    if (inviter != null) {
      dto.setInviterName(inviter.getName());
    }

    if (invitee != null) {
      dto.setInviteeName(invitee.getName());
    }
  }

  // Methods to create response objects
  default InvitationListResponse toInvitationListResponse(List<InvitationDto> data) {
    InvitationListResponse response = new InvitationListResponse();
    response.setData(data);
    return response;
  }

  default ShopUserListResponse toShopUserListResponse(List<ShopUserDto> data) {
    ShopUserListResponse response = new ShopUserListResponse();
    response.setData(data);
    return response;
  }
}
