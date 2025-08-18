package org.qubership.cloud.dbaas.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

public class DigestUtil {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static String calculateDigest(Object obj) {
        try {
            String json = objectMapper.writeValueAsString(obj);

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes());

            return Base64.getEncoder().encodeToString(hash);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
