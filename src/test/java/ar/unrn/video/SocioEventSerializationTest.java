package ar.unrn.video;

import ar.unrn.video.event.Event;
import ar.unrn.video.event.SocioPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The Socio consumer receives a generic envelope, {@code Event<String, SocioPayload>}.
 * Generic type information survives only because Spring AMQP infers it from the listener
 * method signature, so this test pins that behaviour: it is the one thing that silently
 * degrades into a LinkedHashMap if the converter is ever reconfigured.
 */
class SocioEventSerializationTest {

    /** Mirrors SocioEventListener#onSocioEvent, used only to read its generic parameter type. */
    @SuppressWarnings("unused")
    private void listenerSignature(Event<String, SocioPayload> event) {
    }

    private JacksonJsonMessageConverter converter() {
        JacksonJsonMessageConverter converter = new JacksonJsonMessageConverter(JsonMapper.builder().build());
        converter.setAlwaysConvertToInferredType(true);
        return converter;
    }

    private MessageProperties propertiesWithInferredEventType() throws NoSuchMethodException {
        Method method = SocioEventSerializationTest.class
                .getDeclaredMethod("listenerSignature", Event.class);

        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setInferredArgumentType(method.getGenericParameterTypes()[0]);
        return props;
    }

    @Test
    @DisplayName("A CREATE envelope deserializes into a typed Event with a typed SocioPayload")
    void deserializesTypedEnvelope() throws Exception {
        String json = """
                {
                  "aggregate":"Socio",
                  "type":"CREATE",
                  "key":"de51c71a-319e-483c-dd64-dbf11899efb1",
                  "data":{
                    "keycloakId":"de51c71a-319e-483c-dd64-dbf11899efb1",
                    "email":"socio@unrn.edu.ar",
                    "username":"socionuevo",
                    "nombre":"Juan",
                    "apellido":"Perez"
                  }
                }
                """;

        Message message = new Message(json.getBytes(StandardCharsets.UTF_8), propertiesWithInferredEventType());

        Object converted = converter().fromMessage(message);

        Event<?, ?> event = assertInstanceOf(Event.class, converted);
        assertEquals("Socio", event.aggregate());
        assertEquals(Event.Type.CREATE, event.type());
        assertEquals("de51c71a-319e-483c-dd64-dbf11899efb1", event.key());

        SocioPayload payload = assertInstanceOf(SocioPayload.class, event.data(),
                "data must be a SocioPayload, not a raw Map");
        assertEquals("socio@unrn.edu.ar", payload.email());
        assertEquals("socionuevo", payload.username());
        assertEquals("Juan", payload.nombre());
    }

    @Test
    @DisplayName("A DELETE envelope carries only the business key")
    void deserializesDeleteEnvelope() throws Exception {
        String json = """
                {
                  "aggregate":"Socio",
                  "type":"DELETE",
                  "key":"de51c71a-319e-483c-dd64-dbf11899efb1",
                  "data":{"keycloakId":"de51c71a-319e-483c-dd64-dbf11899efb1"}
                }
                """;

        Message message = new Message(json.getBytes(StandardCharsets.UTF_8), propertiesWithInferredEventType());

        Event<?, ?> event = assertInstanceOf(Event.class, converter().fromMessage(message));

        assertEquals(Event.Type.DELETE, event.type());
        SocioPayload payload = assertInstanceOf(SocioPayload.class, event.data());
        assertEquals("de51c71a-319e-483c-dd64-dbf11899efb1", payload.keycloakId());
        assertNull(payload.email());
    }

    @Test
    @DisplayName("The routing key is derived and never serialized into the message body")
    void routingKeyIsDerivedAndNotSerialized() {
        Event<String, SocioPayload> event = Event.of(
                "Socio", Event.Type.UPDATE, "abc", SocioPayload.ofKey("abc"));

        assertEquals("Socio.UPDATE", event.routingKey());

        String body = new String(
                converter().toMessage(event, new MessageProperties()).getBody(), StandardCharsets.UTF_8);
        assertFalse(body.contains("routingKey"), "routingKey must not leak into the wire format: " + body);
    }

}
