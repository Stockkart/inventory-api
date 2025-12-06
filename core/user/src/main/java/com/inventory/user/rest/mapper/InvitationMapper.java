package com.inventory.user.rest.mapper;

import com.inventory.user.domain.model.Invitation;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.rest.dto.invitation.*;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;

import java.time.Instant;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface InvitationMapper {

  @Mapping(target = "invitationId", ignore = true)
  @Mapping(target = "shopId", source = "shopId")
  @Mapping(target = "inviterUserId", source = "inviterUserId")
  @Mapping(target = "inviteeUserId", ignore = true)
  @Mapping(target = "inviteeEmail", source = "request.inviteeEmail")
  @Mapping(target = "role", source = "request.role")
  @Mapping(target = "status", constant = "PENDING")
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "expiresAt", ignore = true)
  @Mapping(target = "acceptedAt", ignore = true)
  @Mapping(target = "rejectedAt", ignore = true)
  Invitation toEntity(String shopId, String inviterUserId, SendInvitationRequest request);

  @AfterMapping
  default void setInvitationFields(@MappingTarget Invitation invitation, String shopId, String inviterUserId, SendInvitationRequest request) {
    invitation.setInvitationId("invitation-" + java.util.UUID.randomUUID());
    invitation.setCreatedAt(Instant.now());
    invitation.setExpiresAt(Instant.now().plusSeconds(7 * 24 * 3600)); // 7 days expiry
    if (request.getInviteeEmail() != null) {
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
}

