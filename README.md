# springboot-sso y keycloak

## OPEN API
- https://springdoc.org/#spring-data-rest-support
- http://localhost:8080/swagger-ui/index.html


# SSO keycloak

## Keycloak client app web
```bash
curl --request POST 'http://localhost:9090/realms/videoclub/protocol/openid-connect/token' --header 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'client_id=web' --data-urlencode 'username=hmunoz' --data-urlencode 'password=hmunoz' --data-urlencode 'grant_type=password'
```
## Keycloak client app api
```shell
curl --request POST 'http://localhost:9090/realms/videoclub/protocol/openid-connect/token' --header 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'client_id=web' --data-urlencode 'username=hmunoz' --data-urlencode 'password=hmunoz'  --data-urlencode 'client_secret=78BoOMc7ZM2zbHHE3bU5JNnHInuErQVn' --data-urlencode 'grant_type=password'
```
## Test api
```shell
curl --location 'http://localhost:8080/api/tests' --header 'Authorization: Bearer <TOKEN>'
```

## Postman Callection get Token and Test Api 
- [VideoClub con Seguridad.postman_collection.json](postman%2FVideoClub%20con%20Seguridad.postman_collection.json)
- ![img_4.png](docs/img_4.png)

# keycloak Realms, groups and roles, users
## Realms videoclub
- http://localhost:9090/admin/master/console/#/realms/videoclub

![Role](docs/img.png)
![Add Role user Test](docs/img_1.png)
![Add Role user Test](docs/img_2.png)
![Add Role user Test](docs/img_3.png)

## Aauthdebugger Oauth2 and JWT
- https://jwt.io/
- https://oauthdebugger.com/


# Dependencias del proyecto Spring Boot con Maven

## Seguridad
- `spring-boot-starter-security`: Proporciona funcionalidades de seguridad para aplicaciones Spring Boot.
- `spring-boot-starter-oauth2-resource-server`: Permite configurar la aplicación como un servidor de recursos OAuth 2.0.
- `spring-security-oauth2-jose`: Ofrece soporte para el estándar JOSE (JSON Object Signing and Encryption) en Spring Security.

## Web y Validación
- `spring-boot-starter-web`: Starter para construir aplicaciones web, incluyendo RESTful, usando Spring MVC.
- `spring-boot-starter-validation`: Proporciona soporte para la validación de beans.

## Persistencia
- `spring-boot-starter-data-jpa`: Facilita el uso de JPA para la persistencia de datos.
- `postgresql`: Driver JDBC para PostgreSQL.

## Manejo de Errores
- `error-handling-spring-boot-starter`: Librería para manejar errores de forma más efectiva en aplicaciones Spring Boot.

## Documentación API
- `springdoc-openapi-starter-webmvc-ui`: Genera documentación OpenAPI 3 para APIs Spring Boot.

## Métricas y Monitoreo
- `spring-boot-starter-actuator`: Añade endpoints para monitorear y manejar la aplicación.
- `micrometer-registry-prometheus`: Permite exponer métricas en formato Prometheus.

## Utilidades de Desarrollo
- `lombok`: Reduce el código repetitivo (getters, setters, constructores, etc.) mediante anotaciones.
- `spring-boot-devtools`: Proporciona herramientas de desarrollo, incluyendo reinicio automático.
- `spring-boot-docker-compose`: Integración con Docker Compose para Spring Boot.

## Testing
- `spring-boot-starter-test`: Proporciona dependencias comunes para testing en Spring Boot.

### Documentation
- https://ravthiru.medium.com/springboot-oauth2-with-keycloak-for-bearer-client-3a31f608a78
- https://www.baeldung.com/spring-boot-keycloak
- https://medium.com/@bcarunmail/securing-rest-api-using-keycloak-and-spring-oauth2-6ddf3a1efcc2
- https://www.baeldung.com/postman-keycloak-endpoints#1-openid-configuration-endpoint

