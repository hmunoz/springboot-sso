package ar.unrn.video.membership.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Filter that intercepts the MCP 2026-07-28 'server/discover' method.
 * Spring AI MCP 2.0.1 does not implement 'server/discover' natively yet,
 * which causes modern MCP clients (like ChatGPT or Antigravity) to fail
 * during initial discovery.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class McpDiscoverFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if ("/mcp".equals(request.getRequestURI()) && "POST".equalsIgnoreCase(request.getMethod())) {
            byte[] bodyBytes = request.getInputStream().readAllBytes();
            if (bodyBytes.length > 0) {
                try {
                    JsonNode root = objectMapper.readTree(bodyBytes);
                    if (root.has("method") && "server/discover".equals(root.get("method").asText())) {
                        JsonNode idNode = root.get("id");
                        Object id = idNode != null && !idNode.isNull()
                                ? (idNode.isNumber() ? idNode.numberValue() : idNode.asText())
                                : 1;

                        log.info("Intercepted MCP server/discover request (id: {})", id);

                        Map<String, Object> result = Map.of(
                                "supportedVersions", List.of("2026-07-28", "2025-11-25", "2024-11-05"),
                                "capabilities", Map.of(
                                        "tools", Map.of("listChanged", false),
                                        "resources", Map.of("subscribe", false, "listChanged", false),
                                        "prompts", Map.of("listChanged", false)
                                ),
                                "serverInfo", Map.of(
                                        "name", "videoclub-membership-mcp-server",
                                        "version", "1.0.0"
                                ),
                                "instructions", "Herramientas de gestion de socios del VideoClub UNRN: listado y consulta por id. No expone datos de peliculas."
                        );

                        Map<String, Object> rpcResponse = Map.of(
                                "jsonrpc", "2.0",
                                "id", id,
                                "result", result
                        );

                        response.setStatus(HttpServletResponse.SC_OK);
                        response.setContentType("application/json");
                        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                        objectMapper.writeValue(response.getOutputStream(), rpcResponse);
                        return;
                    }
                } catch (Exception e) {
                    log.warn("Failed to inspect MCP request body: {}", e.getMessage());
                }

                // If not server/discover, wrap the request with cached body so downstream filters/servlets can read it
                CachedBodyHttpServletRequest wrappedRequest = new CachedBodyHttpServletRequest(request, bodyBytes);
                filterChain.doFilter(wrappedRequest, response);
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private static class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {
        private final byte[] cachedBody;

        public CachedBodyHttpServletRequest(HttpServletRequest request, byte[] cachedBody) {
            super(request);
            this.cachedBody = cachedBody;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(cachedBody);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return byteArrayInputStream.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                }

                @Override
                public int read() {
                    return byteArrayInputStream.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
