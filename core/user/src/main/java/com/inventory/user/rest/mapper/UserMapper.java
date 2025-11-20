package com.inventory.user.rest.mapper;

import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.rest.dto.auth.LoginResponse;
import com.inventory.user.rest.dto.user.UserDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface UserMapper {

    UserDto toDto(UserAccount account);

    @Mapping(target = "userId", source = "user.userId")
    LoginResponse.UserSummary toUserSummary(UserAccount user);
}

