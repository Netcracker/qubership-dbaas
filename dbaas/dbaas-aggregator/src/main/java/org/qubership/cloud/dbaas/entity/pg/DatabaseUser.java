package org.qubership.cloud.dbaas.entity.pg;

import org.qubership.cloud.dbaas.converter.ConnectionDescriptionConverter;
import org.qubership.cloud.dbaas.converter.MapConverter;
import org.qubership.cloud.dbaas.dto.v3.GetOrCreateUserRequest;
import org.qubership.cloud.dbaas.dto.ConnectionDescription;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import org.apache.commons.lang3.StringUtils;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@Entity(name = "DatabaseUser")
@Table(name = "database_user")
@Builder
public class DatabaseUser implements Serializable {

    @Id
    @Column(name = "id")
    @Schema(description = "A unique identifier of database user.")
    private UUID userId;

    @ManyToOne
    @JoinColumn(name = "database_id")
    @NotNull
    @Schema(required = true, description = "The logical database to which the user belongs")
    private Database database; // todo arvo must specify on databaseRegistry

    @Column(name = "logical_user_id")
    @Schema(required = false, description = "The logical user id. Required if user specification is 'custom'")
    private String logicalUserId;

    @Column(name = "user_role")
    @NotNull
    @Schema(required = true, description = "The user role. For example, admin, rw, ro, streaming, none")
    private String userRole;

    @Column(name = "connection_description")
    @Convert(converter = ConnectionDescriptionConverter.class)
    @Schema(description = "This parameter describes connection properties.")
    private ConnectionDescription connectionDescription;

    @NotNull
    @Column(name = "creation_method")
    @Enumerated(EnumType.STRING)
    @Schema(required = true, description = "Indicated how the user was created. For example, 'standard' or 'custom'")
    private CreationMethod creationMethod;


    @NotNull
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "connection_properties", columnDefinition = "jsonb")
    @Convert(converter = MapConverter.class)
    @Schema(required = true, description = "The information about connection to database. It contains such keys as url, authDbName, username, password, port, host." +
            "Setting keys depends on the database type.")
    private Map<String, Object> connectionProperties;

    @NotNull
    @Column(name = "created_date", updatable = false, nullable = false)
    @CreationTimestamp
    @Schema(description = "Time to create a user")
    private Date createdDate;

    @NotNull
    @Column(name = "status")
    @Enumerated(EnumType.STRING)
    @Schema(required = true, description = "Contains current status of operation under user")
    private Status status;


    public enum CreationMethod {
        STANDARD,
        ON_REQUEST
    }

    public enum Status {
        CREATING,
        CREATED
    }


    public DatabaseUser(GetOrCreateUserRequest request,
                        DatabaseUser.CreationMethod creationMethod,
                        Database database) {
        this.userId = UUID.randomUUID();
        this.connectionDescription = database.getConnectionDescription();
        this.database = database;
        this.connectionProperties = new HashMap<>();
        this.logicalUserId = request.getLogicalUserId();
        this.creationMethod = creationMethod;
        this.userRole = request.getUserRole();
    }

    @PrePersist
    @PreUpdate
    void insertLogicalUserIdToConnectionProperty() {
        if (StringUtils.isNotBlank(this.logicalUserId)) {
            this.getConnectionProperties().put("logicalUserId", logicalUserId);
        }
    }
}
