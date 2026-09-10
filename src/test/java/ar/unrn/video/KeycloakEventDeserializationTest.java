package ar.unrn.video;

import ar.unrn.video.model.KeycloakEvent;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class KeycloakEventDeserializationTest {

    @Test
    void testDeserializeKeycloakUserEventWithTextPlainContentType() {
        String json = """
                {
                  "type":"REFRESH_TOKEN",
                  "realmId":"4bcc68c6-f2d8-435b-bcab-fe55b4c30d80",
                  "clientId":"videoclub-frontend",
                  "userId":"de51c71a-319e-483c-dd64-dbf11899efb1",
                  "ipAddress":"172.25.0.1",
                  "time":1789041697936,
                  "details":{
                    "token_id":"onrtrt:ea18fb95-2040-5475-a511-62ada1cf572e",
                    "grant_type":"refresh_token"
                  }
                }
                """;

        JsonMapper mapper = JsonMapper.builder().build();
        JacksonJsonMessageConverter converter = new JacksonJsonMessageConverter(mapper);
        converter.setAlwaysConvertToInferredType(true);

        MessageProperties props = new MessageProperties();
        props.setContentType("application/json");
        props.setInferredArgumentType(KeycloakEvent.class);

        Message message = new Message(json.getBytes(StandardCharsets.UTF_8), props);

        Object result = converter.fromMessage(message, KeycloakEvent.class);

        assertNotNull(result);
        assertInstanceOf(KeycloakEvent.class, result);
        KeycloakEvent event = (KeycloakEvent) result;
        assertEquals("REFRESH_TOKEN", event.type());
        assertEquals("de51c71a-319e-483c-dd64-dbf11899efb1", event.userId());
        assertTrue(event.isUserEvent());
    }

    @Test
    void testDeserializeKeycloakAdminEvent() {
        String json = """
                {
                  "time":1789041697936,
                  "realmId":"videoclub",
                  "authDetails":{
                    "realmId":"videoclub",
                    "clientId":"admin-cli",
                    "userId":"admin-123",
                    "ipAddress":"127.0.0.1"
                  },
                  "operationType":"CREATE",
                  "resourceType":"USER",
                  "resourcePath":"users/de51c71a-319e-483c-dd64-dbf11899efb1",
                  "representation":"{\\"username\\":\\"testuser\\"}",
                  "error":null
                }
                """;

        JsonMapper mapper = JsonMapper.builder().build();
        JacksonJsonMessageConverter converter = new JacksonJsonMessageConverter(mapper);
        converter.setAlwaysConvertToInferredType(true);

        MessageProperties props = new MessageProperties();
        props.setContentType("application/json");
        props.setInferredArgumentType(KeycloakEvent.class);

        Message message = new Message(json.getBytes(StandardCharsets.UTF_8), props);

        Object result = converter.fromMessage(message, KeycloakEvent.class);

        assertNotNull(result);
        assertInstanceOf(KeycloakEvent.class, result);
        KeycloakEvent event = (KeycloakEvent) result;
        assertEquals("CREATE", event.operationType());
        assertEquals("USER", event.resourceType());
        assertTrue(event.isAdminEvent());
        assertEquals("de51c71a-319e-483c-dd64-dbf11899efb1", event.extractTargetUserId());
    }
}
