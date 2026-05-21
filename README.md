This is a Keycloak implementation of the [Transaction Tokens](https://datatracker.ietf.org/doc/draft-ietf-oauth-transaction-tokens/) Internet Draft.

## Overview

The draft introduces a concept of a Transaction Token (Txn-Token), which encapsulates the data related to the requesting user (human), requesting workload (non-human) and the transaction itself, and preserves it throughout the entire call chain:
> Transaction Tokens (Txn-Tokens) are designed to maintain and propagate
> user identity, workload identity and authorization context throughout
> the Call Chain within a trusted domain during the processing of external
> requests (e.g. such as API calls) or requests initiated internally
> within the trust domain. Txn-Tokens ensure that this context is
> preserved throughout the Call Chain thereby enhancing security and
> consistency in complex, multi-service architectures.

The draft also introduces Transaction Token Service (TTS), an OAuth 2.0 compliant service that issues Txn-Tokens via OAuth 2.0 Token Exchange. The diagram below shows the typical deployment and data flow for Transaction Tokens:
![overview](docs/img/overview.svg)

### Standard Flow
> [!NOTE]
> Legend:  
> AT = Access Token  
> TT = Transaction Token  
> RCTX = request context (user IP address, auth details…)  
> TCTX = transaction context (amount, …)

![sequence](docs/img/sequence.svg)

## Build
```
$ mvn clean install
```
## Install
Copy the `target/keycloak-tts-*.jar` file into your Keycloak's `providers` directory.

## Configuration   

### Identity Provider
To enable Transaction Token Service, create an OpenID Connect Identity Provider and name it `tts`:
<img width="2190" height="3024" alt="TTS Identity Provider" src="https://github.com/user-attachments/assets/2c74b253-ab84-409e-b0a7-8546bfafe9c9" />


The name `tts` is currently hardcoded, and will be used by the implementation to resolve the identity provider. In the future, Keycloak will have a dedicated "Transaction Token Service" identity provider type.

### Allowed Audience
As per [12.1. Txn-Token Request](https://www.ietf.org/archive/id/draft-ietf-oauth-transaction-tokens-08.html#name-txn-token-request), the `audience` parameter is mandatory, and must be set to the Trust Domain name.

 In Keycloak TTS, this configuration option is also mandatory. Currently, it does not have a UI. To configure trust domain / allowed audience, please use the `kcadm` tool:
 ```
 $ bin/kcadm.sh config credentials --server ${KEYCLOAK_URL} --realm master --user ${KEYCLOAK_ADMIN_USERNAME} --password ${KEYCLOAK_ADMIN_PASSWORD}
 $ bin/kcadm.sh update -r ${TTS_REALM} identity-provider/instances/tts -s 'config."tts.audience"=example.org'
```

## Container Images
The project contains two files: `Dockerfile`  and `Dockerfile.init` .

### Embedded TTS
Use `Dockerfile` to build a self-contained image with Keycloak and TTS JAR embedded.

### Kubernetes Init Container
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
