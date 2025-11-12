package com.netcracker.cloud.dbaas.entity.pg.backupV2;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.SortedMap;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor(force = true)
@Entity(name = "backup_external_database")
@Table(name = "backup_external_database")
public class BackupExternalDatabase {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne
    @JsonBackReference
    @JoinColumn(name = "backup_name")
    private Backup backup;

    @NotNull
    private String name;

    @NotNull
    private String type;

    @NotNull
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<SortedMap<String, Object>> classifiers;
}
