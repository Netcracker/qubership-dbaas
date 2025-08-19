package org.qubership.cloud.dbaas.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.extern.slf4j.Slf4j;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

@Slf4j
public class DigestUtil {

    private static final String ALGORITHM = "SHA-256";
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .build();

    public static String calculateDigest(Object obj) {
        try {
            String json = OBJECT_MAPPER.writeValueAsString(obj);

            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            byte[] hash = digest.digest(json.getBytes());
            String base64Hash = Base64.getEncoder().encodeToString(hash);

            return ALGORITHM + "=" + base64Hash;
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            log.error("Failed to calculate digest", e);
            throw new RuntimeException("Failed to calculate digest", e);
        }
    }
}
