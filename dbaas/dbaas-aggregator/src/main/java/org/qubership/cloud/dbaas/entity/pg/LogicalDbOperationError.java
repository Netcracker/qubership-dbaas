package org.qubership.cloud.dbaas.entity.pg;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity(name = "LogicalDbOperationError")
@Table(name = "logical_db_operation_error")
public class LogicalDbOperationError {

    @Id
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "database_id")
    private Database database;

    @Column(name = "time_db_operation")
    private Date timeDbDeletion;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "http_code")
    private int httpCode;

    @Column(name = "operation")
    @Enumerated(EnumType.STRING)
    private Operation operation;

    public enum Operation {
        DELETE
    }
}

