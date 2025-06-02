package org.qubership.cloud.dbaas.entity.pg.backup;

import org.qubership.cloud.dbaas.converter.ListConverter;
import org.qubership.cloud.dbaas.dto.backup.Status;
import lombok.Data;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@Entity(name = "NamespaceRestoration")
@Table(name = "namespace_restoration")
public class NamespaceRestoration {

    @Id
    private UUID id;

    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    @JoinTable(name = "namespace_restoration_restore_result")
    private List<RestoreResult> restoreResults;

    @Column(name = "fail_reasons")
    @Convert(converter = ListConverter.class)
    private List<String> failReasons = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    private Status status = Status.PROCEEDING;
}
