package ar.unrn.video.model;

import java.time.LocalDateTime;

public record SocioDTO(
        Long id,
        String keycloakId,
        String email,
        String username,
        String nombre,
        String apellido,
        Boolean activo,
        LocalDateTime fechaAlta,
        LocalDateTime fechaBaja
) {}
