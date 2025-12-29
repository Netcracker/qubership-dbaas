package com.netcracker.it.dbaas.entity;

import lombok.*;

import java.util.Map;

@Data
@AllArgsConstructor
@RequiredArgsConstructor
@NoArgsConstructor
public class RestoreUsersRequest {
    @NonNull
    private Map<String, Object> classifier;

    private String role;

    @NonNull
    private String type;
}
