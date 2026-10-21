package id.cermat.user;

import java.util.UUID;

public record UserResponse(UUID id, String username, Role role) {
    public static UserResponse from(User u) {
        return new UserResponse(u.getId(), u.getUsername(), u.getRole());
    }
}
