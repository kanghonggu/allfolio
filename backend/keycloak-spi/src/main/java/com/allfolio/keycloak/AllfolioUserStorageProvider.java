package com.allfolio.keycloak;

import org.keycloak.component.ComponentModel;
import org.keycloak.models.*;
import org.keycloak.storage.UserStorageProvider;
import org.keycloak.storage.user.UserLookupProvider;
import org.keycloak.storage.user.UserQueryProvider;
import org.keycloak.credential.CredentialInput;
import org.keycloak.credential.CredentialInputValidator;
import org.keycloak.models.credential.PasswordCredentialModel;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class AllfolioUserStorageProvider
        implements UserStorageProvider,
        UserLookupProvider,
        CredentialInputValidator,
        UserQueryProvider {

    private final KeycloakSession session;
    private final ComponentModel model;
    // private final AllfolioUserRepository userRepository; // 나중에 붙일 것

    public AllfolioUserStorageProvider(KeycloakSession session,
                                       ComponentModel model
            /* AllfolioUserRepository userRepository */) {
        this.session = session;
        this.model = model;
        // this.userRepository = userRepository;
    }

    @Override
    public void close() {
        // 자원 정리 필요하면 여기
    }


    @Override
    public boolean supportsCredentialType(String s) {
        return PasswordCredentialModel.TYPE.equals(s);
    }

    @Override
    public boolean isConfiguredFor(RealmModel realmModel, UserModel userModel, String s) {
        return supportsCredentialType(s);
    }

    @Override
    public boolean isValid(RealmModel realmModel, UserModel userModel, CredentialInput credentialInput) {
        if (!(credentialInput instanceof org.keycloak.models.UserCredentialModel)) {
            return false;
        }
        if (!supportsCredentialType(credentialInput.getType())) {
            return false;
        }

        // 이메일 기준으로 유저 조회
        String email = userModel.getUsername();
        // TODO: Allfolio DB에서 유저 조회해서 패스워드 검증 (bcrypt 등)
        // boolean matches = passwordEncoder.matches(input.getChallengeResponse(), userEntity.getPasswordHash());
        // return matches;

        return false; // 뼈대 단계에서는 false로 두고, 나중에 구현
    }

    @Override
    public UserModel getUserById(RealmModel realmModel, String s) {
        return null;
    }

    @Override
    public UserModel getUserByUsername(RealmModel realmModel, String s) {
        return null;
    }

    @Override
    public UserModel getUserByEmail(RealmModel realmModel, String s) {
        return getUserByUsername(realmModel, s);
    }

    @Override
    public Stream<UserModel> searchForUserStream(RealmModel realmModel, Map<String, String> map, Integer integer, Integer integer1) {
        return Stream.empty();
    }

    @Override
    public Stream<UserModel> getGroupMembersStream(RealmModel realmModel, GroupModel groupModel, Integer integer, Integer integer1) {
        return Stream.empty();
    }

    @Override
    public Stream<UserModel> searchForUserByUserAttributeStream(RealmModel realmModel, String s, String s1) {
        return Stream.empty();
    }
}
