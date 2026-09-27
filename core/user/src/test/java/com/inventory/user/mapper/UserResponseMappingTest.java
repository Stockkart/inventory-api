package com.inventory.user.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventory.user.domain.model.PlatformRole;
import com.inventory.user.domain.model.UserAccount;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UserResponseMappingTest {

  @Test
  void currentUserCarriesPlatformRoles() {
    UserAccount admin = new UserAccount();
    admin.setUserId("u1");
    admin.setPlatformRoles(Set.of(PlatformRole.PLATFORM_ADMIN));

    assertThat(UserMapper.INSTANCE.toUserResponse(admin).getPlatformRoles())
        .containsExactly(PlatformRole.PLATFORM_ADMIN);
  }

  @Test
  void ordinaryUsersHaveNone() {
    UserAccount user = new UserAccount();
    user.setUserId("u2");

    assertThat(UserMapper.INSTANCE.toUserResponse(user).getPlatformRoles()).isNull();
  }
}
