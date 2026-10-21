package id.cermat.security;

import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;

import id.cermat.user.Role;

/** Identitas pemanggil yang diambil dari token yang SUDAH tervalidasi tanda tangannya. */
public record AuthUser(UUID id, Role role) {
    public static AuthUser from(Jwt jwt) {
        return new AuthUser(UUID.fromString(jwt.getSubject()), Role.valueOf(jwt.getClaimAsString("role")));
    }
}
