package ar.unrn.video.service;

import ar.unrn.video.event.Event;
import ar.unrn.video.event.SocioPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Consumer of the Socio bounded context.
 *
 * <p>It only knows the business exchange and the canonical {@link Event} envelope — never
 * Keycloak. That is what would let this class move to a separate microservice without
 * touching the Anti-Corruption Layer that feeds it.
 *
 * <p>Exceptions are deliberately allowed to propagate: Spring AMQP acknowledges the
 * message only when this method returns normally. Swallowing an exception here would ack
 * a message whose work never happened, losing the event for good.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SocioEventListener {

    private final SocioService socioService;

    @RabbitListener(queues = "${videoclub.rabbitmq.socio-queue:socio.events.queue}")
    public void onSocioEvent(final Event<String, SocioPayload> event) {
        log.info("Received domain event [{}.{}] for key [{}]",
                event.aggregate(), event.type(), event.key());

        if (event.type() == null || event.data() == null) {
            log.warn("Discarding malformed domain event (missing type or data): {}", event);
            return;
        }

        final SocioPayload payload = event.data();

        try {
            switch (event.type()) {
                case CREATE -> socioService.crearSocioDesdeEvento(payload);
                case UPDATE -> socioService.actualizarSocioDesdeEvento(payload);
                case DELETE -> socioService.darDeBajaSocioDesdeEvento(payload.keycloakId());
            }
        } catch (DataIntegrityViolationException e) {
            // The unique constraint on keycloakId rejected a concurrent duplicate.
            // At-least-once delivery makes this expected, not exceptional: the member
            // already exists, so the event is effectively processed.
            log.info("Socio [{}] already persisted by a concurrent consumer, event ignored",
                    payload.keycloakId());
        }
    }

}
