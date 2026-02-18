package pro.carretti.keycloak.tts;

import org.jboss.logging.Logger;
import org.keycloak.Config.Scope;
import org.keycloak.broker.oidc.OIDCIdentityProviderConfig;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.protocol.oidc.TokenExchangeProvider;
import org.keycloak.protocol.oidc.TokenExchangeProviderFactory;

public class TTSTokenExchangeProviderFactory implements TokenExchangeProviderFactory {

    private static final String PROVIDER_ID = "transaction-token-service";
    private static final Logger LOG = Logger.getLogger(TTSTokenExchangeProviderFactory.class);

    private OIDCIdentityProviderConfig config;

    @Override
    public TokenExchangeProvider create(KeycloakSession session) {
        return new TTSTokenExchangeProvider(session);
    }

    @Override
    public void init(Scope scope) {
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
    }

    @Override
    public void close() {
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public int order() {
        return 100;
    }

}
