package com.netcracker.it.dbaas.entity;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class FailureRegistrationV3 {
    @NonNull
    private String id;

    @NonNull
    private String message;
}