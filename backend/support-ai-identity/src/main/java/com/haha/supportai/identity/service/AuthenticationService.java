package com.haha.supportai.identity.service;

import java.util.Locale;

import com.haha.supportai.data.identity.entity.po.AppUser;
import com.haha.supportai.data.identity.repository.AppUserRepository;
import com.haha.supportai.identity.entity.dto.AuthenticatedUser;
import com.haha.supportai.identity.exception.InvalidCredentialsException;
import com.haha.supportai.identity.security.PasswordHasher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthenticationService {

    private final AppUserRepository appUserRepository;
    private final PasswordHasher passwordHasher;

    public AuthenticatedUser authenticate(String username, String password) {
        String normalizedUsername = normalizeUsername(username);
        AppUser user = appUserRepository.findByUsername(normalizedUsername)
                .orElseThrow(InvalidCredentialsException::new);
        if (!user.isEnabled() || !passwordHasher.matches(password, user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        return AuthenticatedUser.from(user);
    }

    public static String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }
}
