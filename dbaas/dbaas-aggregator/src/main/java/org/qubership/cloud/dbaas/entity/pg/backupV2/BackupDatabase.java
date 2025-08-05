package org.qubership.cloud.dbaas.entity.pg.backupV2;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@RequiredArgsConstructor
@NoArgsConstructor(force = true)
@Entity(name = "backup_database")
@Table(name = "v2_backup_database")
public class BackupDatabase {

    @Id
    @NonNull
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "logical_backup_id")
    private LogicalBackup logicalBackup;

    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String classifiers;

    //TODO settings field can be null need to validate
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String settings;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String users;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String resources;

    @Column(name = "externally_manageable")
    private boolean externallyManageable;

}
