package ar.unrn.video;

import ar.unrn.video.client.keycloak.KeycloakAdminClient;
import ar.unrn.video.client.keycloak.dto.KeycloakUserDTO;
import ar.unrn.video.domain.Socio;
import ar.unrn.video.event.SocioPayload;
import ar.unrn.video.repos.SocioRepository;
import ar.unrn.video.service.SocioService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SocioServiceTest {

    private static final String KEYCLOAK_ID = "de51c71a-319e-483c-dd64-dbf11899efb1";

    private SocioRepository socioRepository;
    private KeycloakAdminClient keycloakAdminClient;
    private SocioService socioService;

    @BeforeEach
    void setUp() {
        socioRepository = mock(SocioRepository.class);
        keycloakAdminClient = mock(KeycloakAdminClient.class);
        socioService = new SocioService(socioRepository, keycloakAdminClient, "videoclub");
    }

    private SocioPayload payload() {
        return new SocioPayload(KEYCLOAK_ID, "socio@unrn.edu.ar", "socionuevo", "Juan", "Perez");
    }

    private Socio socioExistente(boolean activo) {
        Socio socio = new Socio();
        socio.setId(10001L);
        socio.setKeycloakId(KEYCLOAK_ID);
        socio.setUsername("socionuevo");
        socio.setActivo(activo);
        socio.setFechaAlta(LocalDateTime.now().minusDays(5));
        if (!activo) {
            socio.setFechaBaja(LocalDateTime.now().minusDays(1));
        }
        return socio;
    }

    @Test
    @DisplayName("CREATE persists an active member with its registration timestamp")
    void createPersistsActiveMember() {
        when(socioRepository.existsByKeycloakId(KEYCLOAK_ID)).thenReturn(false);

        socioService.crearSocioDesdeEvento(payload());

        ArgumentCaptor<Socio> captor = ArgumentCaptor.forClass(Socio.class);
        verify(socioRepository).save(captor.capture());

        Socio saved = captor.getValue();
        assertEquals(KEYCLOAK_ID, saved.getKeycloakId());
        assertEquals("socio@unrn.edu.ar", saved.getEmail());
        assertEquals("Juan", saved.getNombre());
        assertTrue(saved.getActivo());
        assertNotNull(saved.getFechaAlta());
        assertNull(saved.getFechaBaja());
    }

    @Test
    @DisplayName("CREATE is idempotent: a duplicate event does not write a second member")
    void createIsIdempotent() {
        when(socioRepository.existsByKeycloakId(KEYCLOAK_ID)).thenReturn(true);

        socioService.crearSocioDesdeEvento(payload());

        verify(socioRepository, never()).save(any());
    }

    @Test
    @DisplayName("CREATE tolerates a payload with no email, since Keycloak allows users without one")
    void createToleratesMissingEmail() {
        when(socioRepository.existsByKeycloakId(KEYCLOAK_ID)).thenReturn(false);

        socioService.crearSocioDesdeEvento(new SocioPayload(KEYCLOAK_ID, null, "sinmail", null, null));

        ArgumentCaptor<Socio> captor = ArgumentCaptor.forClass(Socio.class);
        verify(socioRepository).save(captor.capture());
        assertNull(captor.getValue().getEmail());
        assertEquals("sinmail", captor.getValue().getUsername());
    }

    @Test
    @DisplayName("UPDATE refreshes member data without touching membership status")
    void updateRefreshesData() {
        when(socioRepository.findByKeycloakId(KEYCLOAK_ID)).thenReturn(Optional.of(socioExistente(true)));

        socioService.actualizarSocioDesdeEvento(
                new SocioPayload(KEYCLOAK_ID, "nuevo@unrn.edu.ar", "socionuevo", "Juan Carlos", "Perez"));

        ArgumentCaptor<Socio> captor = ArgumentCaptor.forClass(Socio.class);
        verify(socioRepository).save(captor.capture());

        Socio saved = captor.getValue();
        assertEquals("nuevo@unrn.edu.ar", saved.getEmail());
        assertEquals("Juan Carlos", saved.getNombre());
        assertTrue(saved.getActivo());
    }

    @Test
    @DisplayName("UPDATE upserts: an unknown member is created instead of being dropped")
    void updateUpsertsUnknownMember() {
        when(socioRepository.findByKeycloakId(KEYCLOAK_ID)).thenReturn(Optional.empty());
        when(socioRepository.existsByKeycloakId(KEYCLOAK_ID)).thenReturn(false);

        socioService.actualizarSocioDesdeEvento(payload());

        ArgumentCaptor<Socio> captor = ArgumentCaptor.forClass(Socio.class);
        verify(socioRepository).save(captor.capture());
        assertEquals(KEYCLOAK_ID, captor.getValue().getKeycloakId());
        assertTrue(captor.getValue().getActivo());
    }

    @Test
    @DisplayName("DELETE performs a soft delete with a deactivation timestamp")
    void deleteSoftDeletesMember() {
        when(socioRepository.findByKeycloakId(KEYCLOAK_ID)).thenReturn(Optional.of(socioExistente(true)));

        socioService.darDeBajaSocioDesdeEvento(KEYCLOAK_ID);

        ArgumentCaptor<Socio> captor = ArgumentCaptor.forClass(Socio.class);
        verify(socioRepository).save(captor.capture());

        Socio saved = captor.getValue();
        assertFalse(saved.getActivo());
        assertNotNull(saved.getFechaBaja());
    }

    @Test
    @DisplayName("DELETE is idempotent: an already inactive member keeps its original fechaBaja")
    void deleteIsIdempotent() {
        Socio yaInactivo = socioExistente(false);
        LocalDateTime fechaBajaOriginal = yaInactivo.getFechaBaja();
        when(socioRepository.findByKeycloakId(KEYCLOAK_ID)).thenReturn(Optional.of(yaInactivo));

        socioService.darDeBajaSocioDesdeEvento(KEYCLOAK_ID);

        verify(socioRepository, never()).save(any());
        assertEquals(fechaBajaOriginal, yaInactivo.getFechaBaja());
    }

    @Test
    @DisplayName("DELETE for an unknown member is a no-op, not a failure")
    void deleteUnknownMemberIsNoOp() {
        when(socioRepository.findByKeycloakId(KEYCLOAK_ID)).thenReturn(Optional.empty());

        socioService.darDeBajaSocioDesdeEvento(KEYCLOAK_ID);

        verify(socioRepository, never()).save(any());
    }

    @Test
    @DisplayName("Reconciliation provisions pre-existing Keycloak users and mirrors their enabled flag")
    void reconciliationProvisionsExistingUsers() {
        when(keycloakAdminClient.getUsers("videoclub")).thenReturn(List.of(
                new KeycloakUserDTO(KEYCLOAK_ID, "socionuevo", "socio@unrn.edu.ar", "Juan", "Perez", true),
                new KeycloakUserDTO("otro-id", "deshabilitado", "off@unrn.edu.ar", "Ana", "Gomez", false)
        ));
        when(socioRepository.findByKeycloakId(any())).thenReturn(Optional.empty());

        int procesados = socioService.sincronizarDesdeKeycloak();

        assertEquals(2, procesados);

        ArgumentCaptor<Socio> captor = ArgumentCaptor.forClass(Socio.class);
        verify(socioRepository, org.mockito.Mockito.times(2)).save(captor.capture());

        List<Socio> guardados = captor.getAllValues();
        assertTrue(guardados.get(0).getActivo());
        assertNull(guardados.get(0).getFechaBaja());
        assertFalse(guardados.get(1).getActivo());
        assertNotNull(guardados.get(1).getFechaBaja());
    }

}
