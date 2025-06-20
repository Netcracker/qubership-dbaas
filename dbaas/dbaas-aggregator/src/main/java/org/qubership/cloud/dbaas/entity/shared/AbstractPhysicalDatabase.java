package org.qubership.cloud.dbaas.entity.shared;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.qubership.cloud.dbaas.converter.ListConverter;
import org.qubership.cloud.dbaas.converter.MapConverter;
import jakarta.persistence.*;
import lombok.Data;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

@Data
@MappedSuperclass
public abstract class AbstractPhysicalDatabase {

    @Id
    protected String id;

    @Column(name = "physical_database_identifier")
    protected String physicalDatabaseIdentifier;

    protected boolean global;

    @Convert(converter = MapConverter.class)
    protected Map<String, String> labels;

    protected String type;

    @Column(name = "registration_date")
    protected Date registrationDate;

    @Column(name = "roles")
    @Convert(converter = ListConverter.class)
    protected List<String> roles;

    @Column(name = "features", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    protected Map<String, Boolean> features;

    /**
     * <p>Equals {@code true} for {@code unidentified} physical databases.
     * These are physical databases, that were created by mongo-evolution scripts in previous releases
     * (instead of registration) and may have incorrect {@link AbstractPhysicalDatabase#physicalDatabaseIdentifier} value.
     *
     * <p>{@code unidentified} flag indicates that {@link AbstractPhysicalDatabase#physicalDatabaseIdentifier} value
     * needs to be updated during physical database auto-registration.
     */
    @Column(name = "unidentified")
    @JsonIgnore
    @Nullable
    protected Boolean unidentified;

    @Column(name = "ro_host")
    protected String roHost;

    /**
     * Checks if physical database is identified (see javadoc for {@link AbstractPhysicalDatabase#unidentified}).
     *
     * @return {@code true} - if database is identified; {@code false} - otherwise.
     */
    public boolean isIdentified() {
        return !isUnidentified();
    }

    /**
     * Checks if physical database is unidentified (see javadoc for {@link AbstractPhysicalDatabase#unidentified}).
     *
     * @return {@code true} - if database is unidentified; {@code false} - otherwise.
     */
    public boolean isUnidentified() {
        return unidentified != null && unidentified;
    }

}
