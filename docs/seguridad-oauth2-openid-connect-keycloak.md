# Guía Integral de Seguridad: OAuth 2.0, OpenID Connect y Keycloak con Spring Boot y React 19

Esta guía está diseñada como material pedagógico y de referencia arquitectónica para dictar una clase completa sobre seguridad moderna en aplicaciones web distribuidas. Explica los fundamentos teóricos, los flujos estándar de la industria, las configuraciones en Keycloak, la implementación en el backend con **Spring Boot (Java 25)** y el frontend SPA con **React 19**.

---

## 1. Fundamentos Conceptuales: ¿Por qué abandonamos la sesión tradicional?

### El problema de la arquitectura monolítica clásica

En aplicaciones web tradicionales, la seguridad se basaba en **sesiones en memoria del servidor** y **cookies de sesión** (`JSESSIONID`):

- **Estado en el servidor (Stateful)**: Dificulta el escalado horizontal (requiere balanceadores con *sticky sessions* o clusters de Redis).
- **Acoplamiento**: El backend debe gestionar directamente la base de datos de usuarios, almacenamiento de contraseñas (hashing, salting), políticas de expiración, bloqueo de cuentas y autenticación multifactor (MFA).
- **Incompatibilidad moderna**: No escala bien para clientes móviles, SPAs (Single Page Applications) o arquitecturas de microservicios.

### La solución moderna: Servidor de Identidad Desacoplado (IAM)

Delegamos la responsabilidad a un Identity & Access Management (IAM) como **Keycloak**. El backend se convierte en un **Resource Server stateless** que solo valida firmas criptográficas de tokens.

```mermaid
graph TD
    User["Usuario / Browser"] -->|"1. Autenticación"| Keycloak["Keycloak (IAM / Auth Server)"]
    Keycloak -->|"2. Emite Tokens (JWT)"| User
    User -->|"3. Request con Bearer Token"| Backend["Spring Boot (Resource Server)"]
    Backend -->|"4. Valida firma vía JWKS (sin estado)"| Keycloak
    Backend -->|"5. Respuesta autorizada"| User
```

---

## 2. OAuth 2.0 vs. OpenID Connect (OIDC)

Una de las confusiones más frecuentes en la industria es mezclar estos dos conceptos:

| Característica | OAuth 2.0 | OpenID Connect (OIDC) |
| --- | --- | --- |
| **Propósito** | **Autorización** (Delegación de permisos) | **Autenticación** (Identidad del usuario) |
| **Pregunta que responde** | *¿A qué recursos puede acceder esta aplicación?* | *¿Quién es la persona conectada?* |
| **Artefacto principal** | `access_token` (normalmente opaco o JWT) | `id_token` (siempre JWT firmado) |
| **Análogo en la vida real** | La llave magnética de una habitación de hotel | El Documento Nacional de Identidad / Pasaporte |

> **Regla de oro**: OAuth 2.0 le da permiso al cliente para realizar acciones en nombre del usuario. OpenID Connect le informa al cliente quién es el usuario. OIDC es una capa construida **encima** de OAuth 2.0.

### Anatomía de los Tokens

1. **`id_token`**: Consumido exclusivamente por el cliente (ej. SPA React 19) para personalizar la interfaz (nombre, avatar, email). **Nunca debe enviarse como credencial al backend**.
2. **`access_token`**: Credencial enviada en el header `Authorization: Bearer <token>` hacia las APIs del backend.
3. **`refresh_token`**: Token de larga duración para obtener nuevos `access_token` cuando expiran, sin forzar al usuario a ingresar credenciales nuevamente.

### Estructura de un JWT (JSON Web Token)

Un JWT consta de 3 partes codificadas en Base64URL separadas por puntos (`.`):

- **Header**: Algoritmo (`RS256`) y clave pública identificadora (`kid`).
- **Payload (Claims)**: Sujeto (`sub`), emisor (`iss`), expiración (`exp`), roles y grupos.
- **Signature**: Firma criptográfica generada con la clave privada del servidor de identidad.

---

## 3. Clientes en Keycloak: Públicos vs. Confidenciales

En Keycloak configuramos dos clientes con naturalezas y niveles de confianza completamente distintos:

```mermaid
classDiagram
    class KeycloakClients {
    }
    class VideoclubFrontend {
        +Tipo: Public
        +Entorno: Browser / SPA (React 19)
        +Secret: No posee
        +Flujo: Auth Code + PKCE (S256)
    }
    class VideoclubBackend {
        +Tipo: Confidential
        +Entorno: Servidor / Spring Boot
        +Secret: Almacenado de forma segura
        +Flujo: Client Credentials (M2M)
    }
    KeycloakClients <|-- VideoclubFrontend
    KeycloakClients <|-- VideoclubBackend
```

### A. Cliente Público: `videoclub-frontend` (SPA / Mobile)

- **Problema de seguridad**: El código JavaScript corre en el navegador de los usuarios; cualquier usuario puede inspeccionar la memoria o el código fuente. Por ende, **no puede guardar un `client_secret` de forma segura**.
- **Mecanismo de protección**: **PKCE (Proof Key for Code Exchange)**.

### B. Cliente Confidencial: `videoclub-backend` (Servidor a Servidor)

- Corre en un entorno seguro (el proceso Java de Spring Boot).
- Almacena un secreto (`client_secret = dstNSsANvqlaGfZCJa1mcYzP1EBAYP4N`) fuera del alcance del público.

---

## 4. Flujos de Autorización (Grant Types)

### Flujo 1: Authorization Code Flow con PKCE (S256)

Este es el flujo obligatorio para aplicaciones web y móviles (SPAs).

```mermaid
sequenceDiagram
    autonumber
    actor Usuario
    participant SPA as Frontend (React 19 SPA)
    participant Keycloak as Keycloak (Auth Server)
    participant API as Spring Boot (API)

    Usuario->>SPA: Accede a la aplicación
    SPA->>SPA: Genera Code Verifier aleatorio<br/>y calcula Code Challenge = SHA256(verifier)
    SPA->>Keycloak: Redirige al login:<br/>/auth?response_type=code&client_id=videoclub-frontend<br/>&code_challenge=...&code_challenge_method=S256
    Usuario->>Keycloak: Ingresa credenciales (usuario/password + MFA)
    Keycloak->>SPA: Redirige con Authorization Code temporal:<br/>/callback?code=AUTH_CODE_123
    SPA->>Keycloak: POST /token<br/>client_id=videoclub-frontend<br/>&code=AUTH_CODE_123&code_verifier=ORIGINAL_VERIFIER
    Keycloak->>Keycloak: Valida que SHA256(verifier) == challenge original
    Keycloak->>SPA: Retorna { access_token, id_token, refresh_token }
    SPA->>API: GET /movies con Header Authorization: Bearer access_token
    API->>API: Valida firma criptográfica localmente vía JWKS
    API->>SPA: Retorna datos 200 OK
```

#### ¿Por qué es vital PKCE?

Evita el ataque de interceptación del código de autorización (*Authorization Code Interception Attack*). Aunque un atacante intercepte el `code` en la redirección, no puede canjearlo en `/token` porque no conoce el `code_verifier` original que generó el frontend en memoria.

---

### Flujo 2: Client Credentials Flow (M2M - Machine to Machine)

Este flujo se utiliza cuando un backend o proceso batch necesita comunicarse con otro servicio o con el Admin API de Keycloak sin intervención humana.

```mermaid
sequenceDiagram
    autonumber
    participant Backend as Spring Boot (videoclub-backend)
    participant Keycloak as Keycloak (Admin API)

    Backend->>Keycloak: POST /realms/videoclub/protocol/openid-connect/token<br/>grant_type=client_credentials<br/>&client_id=videoclub-backend<br/>&client_secret=dstNSsANvq...
    Keycloak->>Backend: Retorna access_token para service account
    Backend->>Keycloak: POST /admin/realms/videoclub/users<br/>Authorization: Bearer service_account_token<br/>{ "username": "nuevo.usuario", ... }
    Keycloak->>Backend: 201 Created
```

---

## 5. Gestión del Ciclo de Vida de Identidades en Keycloak

### Auto-registro y Asignación Automática de Grupos

Para permitir que nuevos usuarios se registren por sí mismos en la plataforma respetando el **Principio de Menor Privilegio**:

1. **Auto-registro activado (`registrationAllowed: true`)**: Habilita el enlace "Registrarse" en el formulario de login de Keycloak.
2. **Grupo por defecto (`defaultGroups: ["/videoclub-default/cliente"]`)**: Todo usuario que se registre se incorpora automáticamente al grupo de clientes, heredando `ROLE_CLIENT` y `movie-permission-read`. De este modo, no tiene permisos administrativos ni acceso a la gestión de otros usuarios.

```mermaid
graph LR
    Reg["Nuevo Usuario se Registra"] -->|"Keycloak Realm"| KC["Asigna defaultGroups"]
    KC --> Group["/videoclub-default/cliente"]
    Group --> Roles["ROLE_CLIENT + movie-permission-read"]
    Group -.->|"Sin permisos"| NoAdmin["Sin user-permission-*"]
```

### Autenticación Multifactor (MFA/2FA) Obligatoria

Para proteger las cuentas de usuario contra ataques de fuerza bruta y robo de contraseñas, Keycloak implementa **TOTP (Time-based One-Time Password)**:

- **Subflujo `Browser - Conditional OTP`**: Contiene la condición `conditional-user-configured`.
- **Acción requerida por defecto (`CONFIGURE_TOTP`, `defaultAction: true`)**:
  - Al registrarse o crearse una cuenta nueva, Keycloak le impone al usuario la tarea obligatoria de configurar un autenticador.
  - En el primer inicio de sesión, el flujo redirige automáticamente a la pantalla de enrolamiento de segundo factor con el código QR.
  - Una vez escaneado con Google Authenticator o FreeOTP, la condición se cumple y a partir de ese momento se solicita el código de 6 dígitos en cada login.

```mermaid
sequenceDiagram
    autonumber
    actor Usuario
    participant Browser
    participant Keycloak

    Usuario->>Browser: Click en "Registrarse"
    Browser->>Keycloak: Envía formulario de registro
    Keycloak->>Keycloak: Crea usuario en /videoclub-default/cliente<br/>y asigna Required Action CONFIGURE_TOTP
    Keycloak->>Browser: Muestra pantalla de enrolamiento con Código QR
    Usuario->>Keycloak: Escanea QR en celular e ingresa código de 6 dígitos
    Keycloak->>Keycloak: Valida y registra credencial TOTP
    Keycloak->>Browser: Emite sesión y redirige a la SPA
```

---

## 6. Modelado de Roles, Grupos y Prevención de "Token Bloat"

### Jerarquía de Roles y Separación de Dominios

Para aplicar el principio de menor privilegio, separamos los dominios de datos:

1. **Dominio Películas**: `movie-permission-read`, `movie-permission-create`, `movie-permission-update`, `movie-permission-delete`.
2. **Dominio Usuarios**: `user-permission-read`, `user-permission-create`.

```mermaid
graph TD
    Root["Grupo Raíz: /videoclub-default"]
    Root --> Admin["Subgrupo: administrador"]
    Root --> Cliente["Subgrupo: cliente"]

    Admin -->|"Realm Role"| RoleAdmin["ROLE_ADMIN"]
    Admin -->|"Client Roles (videoclub-frontend)"| MovieAdmin["movie-permission-* (CRUD)"]
    Admin -->|"Client Roles (videoclub-frontend)"| UserAdmin["user-permission-* (CR)"]

    Cliente -->|"Realm Role"| RoleClient["ROLE_CLIENT"]
    Cliente -->|"Client Roles (videoclub-frontend)"| MovieRead["movie-permission-read"]
```

### Prevención de Token Bloat (`fullScopeAllowed: false`)

Por defecto, Keycloak asocia todos los roles del realm a cada token. Esto infla innecesariamente el tamaño de los headers HTTP en cada petición.

- En `realm-export.json`, configuramos `"fullScopeAllowed": false` en `videoclub-frontend`.
- Definimos un Scope explícito (`videoclub`) mapeando únicamente los roles de negocio necesarios.

---

## 7. Implementación en el Backend: Spring Boot Resource Server

### Configuración en `application.yml`

Spring Boot 4 / Spring Security 7 valida los tokens de forma completamente desacoplada mediante el endpoint público de certificados (JWKS) de Keycloak:

```yaml
spring:
  security:
    oauth2:
      resource-server:
        jwt:
          issuer-uri: http://localhost:9091/realms/videoclub
          jwk-set-uri: http://localhost:9091/realms/videoclub/protocol/openid-connect/certs
```

### Configuración de CORS para el Frontend SPA

En `SecurityConfiguration.java`, registramos un bean `CorsConfigurationSource` para permitir las peticiones cross-origin desde `http://localhost:5173`:

```java
@Bean
public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(List.of("http://localhost:5173", "http://localhost:3000"));
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD"));
    configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "X-Requested-With"));
    configuration.setAllowCredentials(true);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
}
```

### Conversión de Claims: `KeycloakGrantedAuthoritiesConverter`

Por defecto, Spring Security busca roles en el claim `scope` o `scp`. Keycloak guarda los roles en:

- `realm_access.roles` (roles de realm, ej. `ROLE_ADMIN`).
- `resource_access.<client-id>.roles` (roles de cliente, ej. `movie-permission-read`).

Implementamos un `Converter<Jwt, Collection<GrantedAuthority>>` que extrae ambas estructuras para poder evaluarlas con `@PreAuthorize`:

```java
@Component
public class KeycloakGrantedAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    @Override
    public Collection<GrantedAuthority> convert(final Jwt jwt) {
        final List<GrantedAuthority> authorities = new ArrayList<>();

        // 1. Roles del Realm
        final Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess != null && realmAccess.get("roles") instanceof List<?> roles) {
            roles.stream()
                .map(role -> new SimpleGrantedAuthority(role.toString()))
                .forEach(authorities::add);
        }

        // 2. Roles del Cliente (videoclub-frontend)
        final Map<String, Object> resourceAccess = jwt.getClaim("resource_access");
        if (resourceAccess != null) {
            resourceAccess.values().stream()
                .filter(Map.class::isInstance)
                .map(m -> (Map<?, ?>) m)
                .filter(client -> client.get("roles") instanceof List<?>)
                .flatMap(client -> ((List<?>) client.get("roles")).stream())
                .map(role -> new SimpleGrantedAuthority(role.toString()))
                .forEach(authorities::add);
        }

        return authorities;
    }
}
```

### Seguridad a Nivel de Método (Method Security)

Activamos `@EnableMethodSecurity` y protegemos los controladores con autorizaciones declarativas:

```java
// Consulta de usuarios: Solo administradores con permiso explícito
@GetMapping("/api/users")
@PreAuthorize("hasAuthority('user-permission-read')")
public ResponseEntity<List<KeycloakUserDTO>> getAllUsers() {
    return ResponseEntity.ok(userService.findAllUsers());
}

// Creación de películas: Solo quien tenga movie-permission-create
@PostMapping("/movies")
@PreAuthorize("hasAuthority('movie-permission-create')")
public ResponseEntity<Long> createMovie(@RequestBody @Valid final MovieDTO movieDTO) {
    return new ResponseEntity<>(movieService.create(movieDTO), HttpStatus.CREATED);
}
```

---

## 8. Cliente Declarativo HTTP Interface (Spring Boot 4)

En Spring Boot 4, el estándar para consumir APIs REST externas son las **Declarative HTTP Interfaces** (anotadas con `@HttpExchange`), reemplazando herramientas pesadas como OpenFeign.

### La Interfaz HTTP

```java
@HttpExchange("/admin/realms/{realm}")
public interface KeycloakAdminClient {

    @GetExchange("/users")
    List<KeycloakUserDTO> getUsers(@PathVariable("realm") String realm);

    @PostExchange("/users")
    ResponseEntity<Void> createUser(
            @PathVariable("realm") String realm,
            @RequestBody KeycloakUserCreateDTO userCreateDTO);
}
```

### El Interceptor M2M con Client Credentials

El interceptor solicita automáticamente el token del cliente confidencial `videoclub-backend` y lo inyecta en cada llamada hacia Keycloak:

```mermaid
graph LR
    Resource["UserResource"] --> Service["UserService"]
    Service --> Client["KeycloakAdminClient (@HttpExchange)"]
    Client --> Interceptor["KeycloakAuthInterceptor"]
    Interceptor -->|"1. Pide token M2M si expiró"| KC_Token["Keycloak /token"]
    Interceptor -->|"2. Agrega Bearer Token"| KC_Admin["Keycloak /admin/..."]
```

---

## 9. Manejo Global de Excepciones Nativo: RFC 7807 (Problem Details)

En lugar de utilizar librerías de terceros o devolver respuestas de error dispares, Spring Boot 3+ y 4 estandarizan los errores bajo la norma **RFC 7807 (`application/problem+json`)**.

Activación en `application.yml`:

```yaml
spring:
  mvc:
    problemdetails:
      enabled: true
```

### Implementación en `GlobalExceptionHandler`

Heredamos de `ResponseEntityExceptionHandler` y formateamos respuestas limpias y tipadas:

```java
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    // Errores de validación @Valid (HTTP 400)
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            final MethodArgumentNotValidException ex,
            final HttpHeaders headers,
            final HttpStatusCode status,
            final WebRequest request) {
        final ProblemDetail problemDetail = ex.getBody();
        final Map<String, String> fieldErrors = new HashMap<>();
        for (final FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(error.getField(), error.getDefaultMessage());
        }
        problemDetail.setProperty("invalidFields", fieldErrors);
        problemDetail.setProperty("timestamp", Instant.now());
        return createResponseEntity(problemDetail, headers, status, request);
    }

    // Errores de autorización @PreAuthorize (HTTP 403)
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDeniedException(final AccessDeniedException ex) {
        final ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN,
                "Acceso denegado. No posee las autoridades requeridas para esta operación."
        );
        problemDetail.setTitle("Access Denied");
        problemDetail.setProperty("timestamp", Instant.now());
        return problemDetail;
    }

    // Propagación de errores downstream de Keycloak (HTTP 409, 400, etc.)
    @ExceptionHandler(HttpClientErrorException.class)
    public ProblemDetail handleHttpClientErrorException(final HttpClientErrorException ex) {
        final ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                ex.getStatusCode(),
                ex.getResponseBodyAsString()
        );
        problemDetail.setTitle("Downstream Service Error");
        problemDetail.setProperty("timestamp", Instant.now());
        return problemDetail;
    }
}
```

---

## 10. Implementación en el Frontend: SPA con React 19

El frontend [`react-sso`](file:///home/horacio/proyectos/unrn/taller/react-sso) actúa como cliente público y demuestra cómo se traslada la seguridad del token a la experiencia de usuario.

### Configuración OIDC con `oidc-client-ts` y `react-oidc-context`

En `src/config.ts`:

```typescript
export const userManager = new UserManager({
  authority: 'http://localhost:9091/realms/videoclub',
  client_id: 'videoclub-frontend',
  redirect_uri: window.location.origin + '/',
  post_logout_redirect_uri: window.location.origin + '/',
  response_type: 'code', // PKCE activado por defecto
  scope: 'openid profile email videoclub',
  userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  automaticSilentRenew: true
});
```

### Extracción de Permisos: Hook `usePermissions`

Un hook de React que decodifica el JWT del token de acceso y expone métodos de verificación:

```typescript
export function usePermissions() {
  const auth = useAuth();
  const tokenData = parseJwt(auth.user?.access_token);
  const clientRoles = tokenData?.resource_access?.['videoclub-frontend']?.roles || [];
  const realmRoles = tokenData?.realm_access?.roles || [];

  return {
    isAuthenticated: auth.isAuthenticated,
    accessToken: auth.user?.access_token,
    hasPermission: (perm: string) => clientRoles.includes(perm),
    hasRole: (role: string) => realmRoles.includes(role),
    clientRoles,
    realmRoles
  };
}
```

### Guardas de Rutas: Componente `PermissionGuard`

Protege las vistas y muestra un mensaje amigable en caso de rechazo:

```tsx
<Route
  path="/users"
  element={
    <PermissionGuard permission="user-permission-read">
      <UsersView />
    </PermissionGuard>
  }
/>
```

---

## 11. Laboratorio Práctico: Guía Paso a Paso para la Clase

### Paso 1: Levantar la infraestructura

```bash
# Red común, base de datos y RabbitMQ
docker compose -f docker/services.yaml up -d

# Servidor de correo de desarrollo
docker compose -f docker/email.yaml up -d

# Keycloak con importación automática del realm
docker compose -f docker/keycloak.yaml --env-file docker/.env up -d
```

### Paso 2: Iniciar Backend y Frontend

```bash
# Terminal 1: Backend Spring Boot
./mvnw spring-boot:run -Dspring-boot.run.profiles=local

# Terminal 2: Frontend React 19
cd ../react-sso
npm run dev
```

### Paso 3: Casos de Prueba Didácticos

#### Caso 1: Intentar acceder a un recurso protegido sin token

```bash
curl -i http://localhost:8080/movies
```

- **Resultado esperado**: `401 Unauthorized`.

#### Caso 2: Login como Cliente y verificar restricciones de permisos

1. Iniciar sesión en `http://localhost:5173` con `usuariocliente` / `usuariocliente`.
2. Verificar que ve la pestaña **Películas**, pero la pestaña **Usuarios** no aparece en la navegación.
3. Si intenta forzar la URL `http://localhost:5173/users`, `PermissionGuard` renderiza el mensaje de acceso restringido (403).

#### Caso 3: Login como Administrador y verificar acceso pleno

1. Iniciar sesión con `usuarioadmin` / `usuarioadmin`.
2. Verificar que visualiza ambas pestañas.
3. Crear una película y dar de alta un nuevo usuario en Keycloak desde la UI.

#### Caso 4: Auto-registro de Usuario y Enrolamiento TOTP en Vivo

1. En la pantalla de login de Keycloak (`http://localhost:5173`), hacer click en **"Registrarse"**.
2. Completar el formulario de registro (ej. `nuevoalumno` / `Password123!`).
3. Keycloak presentará la pantalla obligatoria de **Configurar OTP** con el código QR.
4. Escanear el código con la aplicación móvil (Google Authenticator o FreeOTP) e ingresar el token de 6 dígitos.
5. Al ingresar a la aplicación:
   - Verificará que solo tiene acceso a **Películas** (incorporado automáticamente a `/videoclub-default/cliente`).
   - Al cerrar sesión y volver a ingresar, Keycloak exigirá contraseña y el token OTP de 6 dígitos.

---

## Resumen Arquitectónico para la Pizarra

```
+-------------------------------------------------------------------------------+
|                                  BROWSER                                      |
|                                                                               |
|  [ React 19 SPA ]  ====== (1. Auth Code + PKCE + OTP) =====> [ KEYCLOAK ]     |
|         ||                                                        ||          |
|  (2. Bearer JWT)                                           (Valida firma vía  |
|         ||                                                       JWKS)        |
|         \/                                                        \/          |
|  [ Spring Boot Resource Server ] <================================+           |
|         ||                                                                    |
|   (@HttpExchange)                                                             |
|   (3. Client Credentials M2M)                                                 |
|         ||                                                                    |
|         \/                                                                    |
|  [ Keycloak Admin API ] ===== (Crea usuarios, defaultGroups, TOTP)            |
+-------------------------------------------------------------------------------+
```
