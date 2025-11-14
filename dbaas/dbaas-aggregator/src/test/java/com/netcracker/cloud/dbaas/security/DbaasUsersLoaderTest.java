package com.netcracker.cloud.dbaas.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netcracker.cloud.dbaas.entity.h2.DbaasUser;
import com.netcracker.cloud.dbaas.repositories.h2.H2DbaasUserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@ExtendWith(MockitoExtension.class)
class DbaasUsersLoaderTest {
    @TempDir
    private Path tempDir;

    @Mock
    private H2DbaasUserRepository usersRepository;

    @Test
    void loadDbaasUser() throws IOException {
        UserConfig userConfig1 = new UserConfig();
        userConfig1.setPassword("test-password-1");
        userConfig1.setRoles(List.of("role1", "role2"));
        UserConfig userConfig2 = new UserConfig();
        userConfig2.setPassword("test-password-2");
        userConfig2.setRoles(List.of("role3", "role4"));

        Map<String, UserConfig> userConfiguration = Map.of(
                "user1", userConfig1,
                "user2", userConfig2
        );

        final String location = tempDir.resolve("users.json").toAbsolutePath().toString();
        try (FileOutputStream out = new FileOutputStream(location)) {
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.writeValue(out, userConfiguration);
        }

        DbaasUsersLoader loader = new DbaasUsersLoader();
        loader.loadDbaasUser(null, usersRepository, location);

        userConfiguration.forEach((username, userConfig) -> {
            DbaasUser dbaasUser = new DbaasUser();
            dbaasUser.setUsername(username);
            dbaasUser.setPassword(userConfig.getPassword());
            dbaasUser.setRoles(userConfig.getRoles());
            Mockito.verify(usersRepository).persist(dbaasUser);
        });
        Mockito.verifyNoMoreInteractions(usersRepository);
    }
}
