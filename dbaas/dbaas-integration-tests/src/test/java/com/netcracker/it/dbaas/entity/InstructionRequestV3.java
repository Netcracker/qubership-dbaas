package com.netcracker.it.dbaas.entity;

import lombok.*;
import java.util.List;

@Data
@NoArgsConstructor
@EqualsAndHashCode
public class InstructionRequestV3 {
    private List<SuccessRegistrationV3> success;
    private FailureRegistrationV3 failure;
}
