package com.haha.supportai.identity.security;

import com.haha.supportai.data.identity.enums.UserRoleEnum;
import com.haha.supportai.identity.entity.dto.AuthenticatedUser;
import com.haha.supportai.identity.exception.IdentityAccessDeniedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AdministratorGuard {

    private final ActiveAccountGuard activeAccountGuard;

    public AuthenticatedUser requireAdministrator() {
        AuthenticatedUser currentUser = activeAccountGuard.currentUser();
        if (currentUser.getRole() != UserRoleEnum.ADMIN) {
            throw new IdentityAccessDeniedException();
        }
        return currentUser;
    }
}
