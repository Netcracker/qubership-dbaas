package org.qubership.cloud.dbaas.entity.shared;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;
import org.qubership.cloud.dbaas.converter.ConnectionDescriptionConverter;
import org.qubership.cloud.dbaas.converter.ListConverter;
import org.qubership.cloud.dbaas.converter.ListMapConverter;
import org.qubership.cloud.dbaas.converter.MapConverter;
import org.qubership.cloud.dbaas.dto.ConnectionDescription;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.*;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.util.*;

@Data
@EqualsAndHashCode
@NoArgsConstructor
@JsonIdentityInfo(
        generator = ObjectIdGenerators.PropertyGenerator.class,
        property = "id")
@MappedSuperclass
public abstract class AbstractDatabase implements Serializable {

    @Schema(description = "A unique identifier of the document in the database. This field may not be used when searching by classifier for security purpose. " +
            "In appears in response when Create database API is executed.")
    @Id
    protected UUID id;

    @Schema(required = true, description = "Old classifier describes the purpose of the database and distinguishes this database from other database in the same namespase. " +
            "It contains such keys as dbClassifier, isService, microserviceName, namespace. Setting keys depends on the database type.")
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "old_classifier", columnDefinition = "jsonb")
    protected SortedMap<String, Object> oldClassifier;

    @Schema(required = true, description = "Classifier describes the purpose of the database and distinguishes this database from other database in the same namespase. " +
            "It contains such keys as dbClassifier, scope, microserviceName, namespace. Setting keys depends on the database type.")
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Deprecated(forRemoval = true)
    @Getter(AccessLevel.NONE)
    @JsonProperty
    protected SortedMap<String, Object> classifier;

    @Schema(required = true, description = "The information about connection to database. It contains such keys as url, authDbName, username, password, port, host." +
            "Setting keys depends on the database type.")
    @Column(name = "connection_properties")
    @Convert(converter = ListMapConverter.class)
    protected List<Map<String, Object>> connectionProperties;

    @Schema(required = true, description = "Namespace where database is placed")
    @Deprecated(forRemoval = true)
    @Getter(AccessLevel.NONE)
    @JsonProperty
    protected String namespace;

    @Schema(required = true, description = "Type of database, for example postgresql or mongodb")
    @Deprecated(forRemoval = true)
    @Getter(AccessLevel.NONE)
    @JsonProperty
    protected String type;

    @Schema(required = true, description = "This field indicates for which adapter the database was created.")
    @Column(name = "adapter_id")
    protected String adapterId;

    @Schema(required = true, description = "Name of database. It may be generated or, if name was specified in a request then it will be specified.")
    protected String name;

    @Schema(description = "A marker indicating that the database will be deleted.")
    @Column(name = "marked_for_drop")
    protected boolean markedForDrop;

    @Schema(description = "Time to create a database")
    @Column(name = "time_db_creation")
    protected Date timeDbCreation;

    @Schema(description = "This field indicates if backup is disabled or not. If true, database would not be backed up. Example: false")
    @Column(name = "backup_disabled")
    protected Boolean backupDisabled;

    @Schema(description = "The list of roles which are related to this logical database. The external security service (e.g. DBaaS Agent) can perform a verification process on this field.")
    @Column(name = "db_owner_roles")
    @Convert(converter = ListConverter.class)
    protected List<String> dbOwnerRoles;

    @Schema(description = "Additional settings for creating a database")
    @Convert(converter = MapConverter.class)
    protected Map<String, Object> settings;

    @Schema(description = "This parameter describes connection properties.")
    @Column(name = "connection_description")
    @Convert(converter = ConnectionDescriptionConverter.class)
    protected ConnectionDescription connectionDescription;

    @Schema(description = "Lists warning messages")
    @Convert(converter = ListConverter.class)
    protected List<String> warnings;

    @Schema(description = "This parameter specifies if a control over the database is not carried out by the DbaaS adapter.")
    @Column(name = "externally_manageable")
    protected boolean externallyManageable = false;

    @Schema(required = false, description = "Database version. It uses for blue-green")
    protected String bgVersion;

    @Column(name = "physical_database_id")
    protected String physicalDatabaseId;

    public void addAllConnectionProperties(List<Map<String, Object>> connectionPropertiesForNewRole) {
        if (this.connectionProperties == null) this.connectionProperties = new ArrayList<>();
        this.connectionProperties.addAll(connectionPropertiesForNewRole);
    }
}