package org.qubership.cloud.dbaas.entity.shared;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.util.Date;
import java.util.SortedMap;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIdentityInfo(
        generator = ObjectIdGenerators.PropertyGenerator.class,
        property = "id")
@MappedSuperclass
public abstract class AbstractDatabaseRegistry implements Serializable {
    @Schema(description = "A unique identifier of the document in the database. This field may not be used when searching by classifier for security purpose. " +
            "In appears in response when Create database API is executed.")
    @Id
    protected UUID id;

    @Schema(description = "Time to create a database")
    @Column(name = "time_db_creation")
    protected Date timeDbCreation;

    @Schema(required = true, description = "Classifier describes the purpose of the database and distinguishes this database from other database in the same namespase. " +
            "It contains such keys as dbClassifier, scope, microserviceName, namespace. Setting keys depends on the database type.")
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    protected SortedMap<String, Object> classifier;

    @Schema(required = true, description = "Namespace where database is placed")
    protected String namespace;

    @Schema(required = true, description = "Type of database, for example postgresql or mongodb")
    protected String type;

}
