# springboot-sso

## keycloak



# OPEN API
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
curl --request GET 'http://localhost:8080/api/tests' --header 'Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCIgOiAiSldUIiwia2lkIiA6ICJzZkJEeENnM3RsU3ozTlN3WFBzQi1fOEZxbENjRTRvajI2THhWbEkwcXZrIn0.eyJleHAiOjE3MjQwMTk1NTYsImlhdCI6MTcyNDAxOTI1NiwianRpIjoiYWJkYmMyOWQtZDllOS00MmI4LWFiYWMtZWE5MWU0Y2NlOTU2IiwiaXNzIjoiaHR0cDovL2xvY2FsaG9zdDo5MDkwL3JlYWxtcy92aWRlb2NsdWIiLCJhdWQiOiJhY2NvdW50Iiwic3ViIjoiYzM2MzA3ZWQtM2JhNC00YTc0LWI5NDYtYzExZjA3MzhiY2RmIiwidHlwIjoiQmVhcmVyIiwiYXpwIjoid2ViIiwic2lkIjoiNzc0ZGEyNmQtODRmZS00NjdhLTk3ZmEtZTM5MDMzMDE1MGYxIiwiYWNyIjoiMSIsImFsbG93ZWQtb3JpZ2lucyI6WyIvKiJdLCJyZWFsbV9hY2Nlc3MiOnsicm9sZXMiOlsib2ZmbGluZV9hY2Nlc3MiLCJhZG1pbiIsInVtYV9hdXRob3JpemF0aW9uIiwiZGVmYXVsdC1yb2xlcy12aWRlb2NsdWIiXX0sInJlc291cmNlX2FjY2VzcyI6eyJhY2NvdW50Ijp7InJvbGVzIjpbIm1hbmFnZS1hY2NvdW50IiwibWFuYWdlLWFjY291bnQtbGlua3MiLCJ2aWV3LXByb2ZpbGUiXX19LCJzY29wZSI6ImVtYWlsIHByb2ZpbGUiLCJlbWFpbF92ZXJpZmllZCI6dHJ1ZSwibmFtZSI6IkhvcmFjaW8gTXXDsW96IiwicHJlZmVycmVkX3VzZXJuYW1lIjoiaG11bm96IiwiZ2l2ZW5fbmFtZSI6IkhvcmFjaW8iLCJmYW1pbHlfbmFtZSI6Ik11w7FveiIsImVtYWlsIjoiaG11bm96QHVucm4uZWR1LmFyIn0.WPnWj5m_U4ATiccqCPA37tCsU8CjSnuwLR1NT7vasmfudVNYhJoq6CRvVJzahM-6RWl9kflts5wqz6YQTw3BJ2hOz7lgm8VTquRU8aiy5jidIqmpZO_OUyhmF046OxbQth7AW4HshTTwRO33ctJ2JZhCeOnfiF2EtQw91jsiVqwbbHi_cnGWuPfJET__DAvznbgdlvJoIURoa9PVb4mhN127RsuVkXlXtS7oAOZ2kaXBSyrYVaMB44hB9HUrrb1RzweujPZ6thj8m3Zrv_DIa-Ln1x53nIauGVmbMehMyHJkZPgEvZvEm9RezfriC-iAAKlL1rxnxrO525StNr_ErQ","expires_in":300,"refresh_expires_in":1800,"ref
resh_token":"eyJhbGciOiJIUzUxMiIsInR5cCIgOiAiSldUIiwia2lkIiA6ICJmNmQyOTJhYS0xMzlhLTQ4M2YtYTU0YS02NDgyNjQ0OGU2YmYifQ.eyJleHAiOjE3MjQwMjEwNTYsImlhdCI6MTcyNDAxOTI1NiwianRpIjoiMTc5
MWZjNjItMDU1Ny00NWYwLTlmNzUtY2Y1N2VmZTRiMmFlIiwiaXNzIjoiaHR0cDovL2xvY2FsaG9zdDo5MDkwL3JlYWxtcy92aWRlb2NsdWIiLCJhdWQiOiJodHRwOi8vbG9jYWxob3N0OjkwOTAvcmVhbG1zL3ZpZGVvY2x1YiIsInN1
YiI6ImMzNjMwN2VkLTNiYTQtNGE3NC1iOTQ2LWMxMWYwNzM4YmNkZiIsInR5cCI6IlJlZnJlc2giLCJhenAiOiJ3ZWIiLCJzaWQiOiI3NzRkYTI2ZC04NGZlLTQ2N2EtOTdmYS1lMzkwMzMwMTUwZjEiLCJzY29wZSI6ImVtYWlsIHdl
Yi1vcmlnaW5zIHJvbGVzIGFjciBwcm9maWxlIGJhc2ljIn0.-loZivl1ksPUtK2RsEYT9L4ufXO6629lUnISgSdjN3kj4_Zv7Z7bWIfytFP5mwctPApfiuG47Lhm74uoHtm6ng'
```


# OpenAPI access with token
- http://localhost:8080/swagger-ui/index.html

# keycloak role Test
## realms videoclub
- http://localhost:9090/admin/master/console/#/realms/videoclub

![Role](docs/role.png)
![Add Role user Test](docs/add-role-user.png)

## oauthdebugger Oauth2 and JWT
- https://jwt.io/
- https://oauthdebugger.com/

### Documentation
- https://ravthiru.medium.com/springboot-oauth2-with-keycloak-for-bearer-client-3a31f608a78
- https://www.baeldung.com/spring-boot-keycloak
- https://medium.com/@bcarunmail/securing-rest-api-using-keycloak-and-spring-oauth2-6ddf3a1efcc2
- https://www.baeldung.com/postman-keycloak-endpoints#1-openid-configuration-endpoint

