package org.qubership.cloud.dbaas.entity.shared;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.NonNull;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.io.Serializable;
import java.util.UUID;

@Data
@EqualsAndHashCode
@MappedSuperclass
@NoArgsConstructor
public abstract class AbstractDbResource implements Serializable {
    public final static String USER_KIND = "user";
    public final static String DATABASE_KIND = "database";

    protected AbstractDbResource(@NonNull String kind, @NonNull String name) {
        this.id = UUID.randomUUID();
        this.kind = kind;
        this.name = name;
    }

    @Id
    @JsonIgnore
    protected UUID id;

    @Schema(required = true, description = "The kind of resource. For example database or user")
    @NonNull
    protected String kind;

    @Schema(required = true, description = "Name of the resource.")
    @NonNull
    protected String name;
}
