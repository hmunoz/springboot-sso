package ar.unrn.video.rest;

import ar.unrn.video.service.SseEmitterManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@Tag(name = "Notifications", description = "Server-Sent Events notifications endpoint")
public class NotificationController {

    private final SseEmitterManager sseEmitterManager;

    @Operation(
            summary = "Subscribe to real-time notifications via SSE",
            description = "Establishes a persistent Server-Sent Events stream for the authenticated user. " +
                    "Accepts Authorization: Bearer header or ?access_token= query parameter.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamNotifications(@AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getSubject();
        String username = jwt.getClaimAsString("preferred_username");
        log.info("Incoming SSE subscription request for user [{}] ({})", username, userId);
        return sseEmitterManager.register(userId);
    }
}
