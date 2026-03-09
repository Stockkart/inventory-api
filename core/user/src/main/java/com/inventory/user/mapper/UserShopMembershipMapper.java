package com.inventory.user.mapper;

import com.inventory.user.domain.model.UserRole;
import com.inventory.user.domain.model.UserShopMembership;
import com.inventory.user.rest.dto.response.UserShopDto;
import com.inventory.user.rest.dto.response.UserShopListResponse;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface UserShopMembershipMapper {

  default UserShopMembership toUserShopMembership(String userId, String shopId, UserRole role,
      String relationship) {
    UserShopMembership m = new UserShopMembership();
    m.setUserId(userId);
    m.setShopId(shopId);
    m.setRole(role != null ? role : UserRole.OWNER);
    m.setRelationship(StringUtils.hasText(relationship) ? relationship : "OWNER");
    m.setActive(true);
    m.setJoinedAt(Instant.now());
    return m;
  }

  default UserShopDto toUserShopDto(String shopId, String shopName, String role, String relationship, Instant joinedAt) {
    return new UserShopDto(
        shopId,
        StringUtils.hasText(shopName) ? shopName : shopId,
        StringUtils.hasText(role) ? role : UserRole.OWNER.name(),
        StringUtils.hasText(relationship) ? relationship : "OWNER",
        joinedAt != null ? joinedAt : Instant.now());
  }

  default UserShopListResponse toUserShopListResponse(List<UserShopDto> shops) {
    return new UserShopListResponse(shops != null ? shops : List.of());
  }
}
