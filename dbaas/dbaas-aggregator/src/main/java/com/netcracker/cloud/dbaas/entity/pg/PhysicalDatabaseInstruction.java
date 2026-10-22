package com.netcracker.cloud.dbaas.entity.pg;

import com.netcracker.cloud.dbaas.converter.PhysicalDatabaseRegistryRequestConverter;
import com.netcracker.cloud.dbaas.dto.InstructionType;
import com.netcracker.cloud.dbaas.dto.v3.FailureRegistrationV3;
import com.netcracker.cloud.dbaas.dto.v3.PhysicalDatabaseRegistryRequestV3;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Data
@Entity(name = "PhysicalDatabaseInstruction")
@Table(name = "physical_database_instruction")
public class PhysicalDatabaseInstruction {
    @Id
    private UUID id;

    @NotNull
    @Column(name = "physical_database_id")
    private String physicalDatabaseId;

    @NotNull
    @Column(name = "context")
    private String context;

    @NotNull
    @Column(name = "instruction_type")
    @Enumerated(EnumType.STRING)
    private InstructionType instructionType;

    @NotNull
    @Column(name = "time_creation")
    private Date timeCreation;

    @NotNull
    @Column(name = "physical_db_reg_request")
    @Convert(converter = PhysicalDatabaseRegistryRequestConverter.class)
    private PhysicalDatabaseRegistryRequestV3 physicalDbRegRequest;

    @Column(name = "failure", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<FailureRegistrationV3> failures = new ArrayList<>();
}

