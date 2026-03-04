This is a Keycloak implementation of the [Transaction Tokens](https://datatracker.ietf.org/doc/draft-ietf-oauth-transaction-tokens/) Internet Draft.   
## Building   
```
mvn clean install
```
### Docker images   
The project contains two files: `Dockerfile`  and `Dockerfile.init` .   
Use `Dockerfile` to build a self-contained image with Keycloak and TTS JAR embedded.   
Use `Dockerfile.init` to build a Kubernetes init container image.   
Example use (with [Codecentric Keycloak.x](https://artifacthub.io/packages/helm/codecentric/keycloakx) chart):   
```
extraInitContainers: |
  - name: keycloak-tts
    image: example/keycloak-tts-init
    imagePullPolicy: IfNotPresent
    command:
      - sh
    args:
      - -c
      - |
        echo "Copying providers..."
        cp -R /tmp/providers/* /providers/
    volumeMounts:
      - name: providers
        mountPath: /providers
extraVolumeMounts: |
  - name: providers
    mountPath: /opt/keycloak/providers
extraVolumes: |
  - name: providers
    emptyDir: {}

```

## Configuration   
To enable Transaction Token Service, create an OpenID Connect Identity Provider and name it `tts`.
