package ar.unrn.video.service;

import ar.unrn.video.client.keycloak.KeycloakAdminClient;
import ar.unrn.video.client.keycloak.dto.KeycloakUserDTO;
import ar.unrn.video.domain.Socio;
import ar.unrn.video.event.SocioPayload;
import ar.unrn.video.model.SocioDTO;
import ar.unrn.video.repos.SocioRepository;
import ar.unrn.video.util.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class SocioService {

    private final SocioRepository socioRepository;
    private final KeycloakAdminClient keycloakAdminClient;
    private final String realm;

    public SocioService(final SocioRepository socioRepository,
                        final KeycloakAdminClient keycloakAdminClient,
                        @Value("${keycloak.admin.realm:videoclub}") final String realm) {
        this.socioRepository = socioRepository;
        this.keycloakAdminClient = keycloakAdminClient;
        this.realm = realm;
    }

    // ---------------------------------------------------------------------
    // Event-driven operations (called from SocioEventListener)
    // ---------------------------------------------------------------------

    /**
     * Idempotent create. Deliveries are at-least-once, so this method must tolerate being
     * called twice with the same payload.
     *
     * <p>A {@code DataIntegrityViolationException} raised by the unique constraint on
     * {@code keycloakId} is deliberately NOT caught here: catching it inside the
     * transaction would leave the transaction marked rollback-only and fail on commit.
     * The listener catches it and treats it as "already processed".
     */
    @Transactional
    public void crearSocioDesdeEvento(final SocioPayload payload) {
        if (socioRepository.existsByKeycloakId(payload.keycloakId())) {
            log.debug("Socio [{}] already exists, ignoring CREATE event", payload.keycloakId());
            return;
        }

        final Socio socio = new Socio();
        socio.setKeycloakId(payload.keycloakId());
        socio.setFechaAlta(LocalDateTime.now());
        socio.setActivo(Boolean.TRUE);
        aplicarDatos(socio, payload);

        socioRepository.save(socio);
        log.info("Socio created from event: keycloakId [{}], username [{}]",
                payload.keycloakId(), payload.username());
    }

    /**
     * Idempotent upsert. Refreshes the member data, and creates the member when it does
     * not exist yet — that covers users that predate this feature and whose CREATE event
     * was never consumed.
     *
     * <p>{@code activo} is intentionally left untouched: membership status is decided by
     * the DELETE event and by reconciliation, never by a data update.
     */
    @Transactional
    public void actualizarSocioDesdeEvento(final SocioPayload payload) {
        final Optional<Socio> existing = socioRepository.findByKeycloakId(payload.keycloakId());

        if (existing.isEmpty()) {
            log.info("Socio [{}] not found on UPDATE event, creating it (upsert)", payload.keycloakId());
            crearSocioDesdeEvento(payload);
            return;
        }

        final Socio socio = existing.get();
        aplicarDatos(socio, payload);
        socioRepository.save(socio);
        log.info("Socio updated from event: keycloakId [{}]", payload.keycloakId());
    }

    /**
     * Idempotent soft delete. Re-processing a DELETE event for an already inactive member
     * is a no-op, so the original {@code fechaBaja} is preserved.
     */
    @Transactional
    public void darDeBajaSocioDesdeEvento(final String keycloakId) {
        final Optional<Socio> existing = socioRepository.findByKeycloakId(keycloakId);

        if (existing.isEmpty()) {
            log.warn("DELETE event for unknown Socio [{}], nothing to deactivate", keycloakId);
            return;
        }

        final Socio socio = existing.get();
        if (Boolean.FALSE.equals(socio.getActivo())) {
            log.debug("Socio [{}] already inactive, ignoring DELETE event", keycloakId);
            return;
        }

        socio.setActivo(Boolean.FALSE);
        socio.setFechaBaja(LocalDateTime.now());
        socioRepository.save(socio);
        log.info("Socio deactivated from event: keycloakId [{}]", keycloakId);
    }

    // ---------------------------------------------------------------------
    // Reconciliation
    // ---------------------------------------------------------------------

    /**
     * Compares the local replica against the source of truth and repairs the gaps.
     *
     * <p>This is not a convenience: the Keycloak SPI drops events when the broker is
     * unreachable, so no messaging pattern alone can guarantee convergence. Membership
     * status is reconciled from Keycloak's {@code enabled} flag, because presence in the
     * source of truth is what ultimately decides whether a member is valid.
     *
     * @return number of members created or updated
     */
    @Transactional
    public int sincronizarDesdeKeycloak() {
        final List<KeycloakUserDTO> usuarios = keycloakAdminClient.getUsers(realm);
        int procesados = 0;

        for (final KeycloakUserDTO usuario : usuarios) {
            if (usuario.id() == null || usuario.id().isBlank()) {
                continue;
            }

            final SocioPayload payload = new SocioPayload(
                    usuario.id(),
                    usuario.email(),
                    usuario.username(),
                    usuario.firstName(),
                    usuario.lastName()
            );

            final Socio socio = socioRepository.findByKeycloakId(usuario.id())
                    .orElseGet(() -> {
                        final Socio nuevo = new Socio();
                        nuevo.setKeycloakId(usuario.id());
                        nuevo.setFechaAlta(LocalDateTime.now());
                        return nuevo;
                    });

            aplicarDatos(socio, payload);
            socio.setActivo(usuario.enabled());
            socio.setFechaBaja(usuario.enabled() ? null : LocalDateTime.now());

            socioRepository.save(socio);
            procesados++;
        }

        log.info("Reconciliation finished: {} member(s) synchronized from realm [{}]", procesados, realm);
        return procesados;
    }

    // ---------------------------------------------------------------------
    // Read model (admin API)
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<SocioDTO> findAll() {
        return socioRepository.findAllByOrderByFechaAltaDesc().stream()
                .map(this::mapToDTO)
                .toList();
    }

    @Transactional(readOnly = true)
    public SocioDTO getById(final Long id) {
        return socioRepository.findById(id)
                .map(this::mapToDTO)
                .orElseThrow(NotFoundException::new);
    }

    // ---------------------------------------------------------------------

    private void aplicarDatos(final Socio socio, final SocioPayload payload) {
        socio.setEmail(payload.email());
        socio.setUsername(payload.username());
        socio.setNombre(payload.nombre());
        socio.setApellido(payload.apellido());
    }

    private SocioDTO mapToDTO(final Socio socio) {
        return new SocioDTO(
                socio.getId(),
                socio.getKeycloakId(),
                socio.getEmail(),
                socio.getUsername(),
                socio.getNombre(),
                socio.getApellido(),
                socio.getActivo(),
                socio.getFechaAlta(),
                socio.getFechaBaja()
        );
    }

}
