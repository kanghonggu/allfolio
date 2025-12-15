package com.allfolio.keycloak;

import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.storage.UserStorageProvider;
import org.keycloak.storage.UserStorageProviderFactory;

public class AllfolioUserStorageProviderFactory
        implements UserStorageProviderFactory<UserStorageProvider> {

    public static final String PROVIDER_ID = "allfolio-user-storage";

    @Override
    public UserStorageProvider create(KeycloakSession session, ComponentModel model) {
        // TODO: 여기서 Allfolio DB 커넥션/리포지토리 주입
        return new AllfolioUserStorageProvider(session, model);
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public void init(org.keycloak.Config.Scope config) {
        // 초기화 로직 필요하면
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // 필요시
    }

    @Override
    public void close() {
        // 필요시
    }
}
