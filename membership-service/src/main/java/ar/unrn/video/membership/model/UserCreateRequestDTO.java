package ar.unrn.video.membership.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UserCreateRequestDTO(
        @NotBlank @Size(max = 50) String username,
        @NotBlank @Email @Size(max = 100) String email,
        String firstName,
        String lastName,
        @NotBlank @Size(min = 6, max = 50) String password,
        String group
) {}
