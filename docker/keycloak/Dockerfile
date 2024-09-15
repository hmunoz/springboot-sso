# Usa la imagen base de Keycloak proporcionada por Bitnami
FROM docker.io/bitnami/keycloak:24.0.4-debian-12-r1

# Copia la librería jar al directorio de extensiones/providers de Keycloak
COPY keycloak-to-rabbit-3.0.5.jar /opt/bitnami/keycloak/providers/keycloak-to-rabbit-3.0.5.jar

# Asegúrate de establecer los permisos correctos
USER root
RUN chown keycloak:keycloak /opt/bitnami/keycloak/providers/keycloak-to-rabbit-3.0.5.jar && \
    chmod 664 /opt/bitnami/keycloak/providers/keycloak-to-rabbit-3.0.5.jar

# Cambiar el usuario de nuevo a keycloak (para seguridad)
USER keycloak