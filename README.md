This is a Keycloak implementation of the [Transaction Tokens](https://datatracker.ietf.org/doc/draft-ietf-oauth-transaction-tokens/) Internet Draft.

## Build
```
$ mvn clean install
```
## Install
Copy the `target/keycloak-tts-*.jar` file into your Keycloak's `providers` directory.

## Configuration   

### Identity Provider
To enable Transaction Token Service, create an OpenID Connect Identity Provider and name it `tts`.

The name `tts` is currently hardcoded, and will be used by the implementation to resolve the identity provider. In the future, Keycloak will have a dedicated "Transaction Token Service" identity provider type.

### Allowed Audience
As per [12.1. Txn-Token Request](https://www.ietf.org/archive/id/draft-ietf-oauth-transaction-tokens-08.html#name-txn-token-request), the `audience` parameter is mandatory, and must be set to the Trust Domain name.

 In Keycloak TTS, this configuration option is also mandatory. Currently, it does not have a UI. To configure trust domain / allowed audience, please use the `kcadm` tool:
 ```
 $ bin/kcadm.sh config credentials --server ${KEYCLOAK_URL} --realm master --user ${KEYCLOAK_ADMIN_USERNAME} --password ${KEYCLOAK_ADMIN_PASSWORD}
 $ bin/kcadm.sh update -r ${TTS_REALM} identity-provider/instances/tts -s 'config."tts.audience"=example.org'
```

### Docker images   
The project contains two files: `Dockerfile`  and `Dockerfile.init` .

#### Embedded TTS
Use `Dockerfile` to build a self-contained image with Keycloak and TTS JAR embedded.

#### Kubernetes Init Container
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
