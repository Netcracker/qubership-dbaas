package org.qubership.cloud.dbaas.entity.pg;

import org.qubership.cloud.dbaas.converter.ClassifierConverter;
import org.qubership.cloud.dbaas.converter.DatabaseConverter;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Date;
import java.util.SortedMap;
import java.util.UUID;

@Data
@NoArgsConstructor
@Entity(name = "DatabaseHistory")
@Table(name = "database_history")
public class DatabaseHistory {

    @Id
    @GeneratedValue
    private UUID id;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Convert(converter = DatabaseConverter.class)
    private Database database;

    @Convert(converter = ClassifierConverter.class)
    private SortedMap<String, Object> classifier;

    private String type;
    private String name;
    private Date date;
    private Integer version;

    @Column(name = "change_action")
    @Enumerated(value = EnumType.STRING)
    private ChangeAction changeAction;

    public DatabaseHistory(DatabaseRegistry databaseRegistry) {
        this.database = databaseRegistry.getDatabase();
        this.classifier = databaseRegistry.getClassifier();
        this.type = databaseRegistry.getType();
        this.name = databaseRegistry.getName();
        this.date = new Date();
    }

    public enum ChangeAction {
        UPDATE_CLASSIFIER,
        UPDATE_CONNECTION_PROPERTIES
    }
}
