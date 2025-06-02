package org.qubership.cloud.dbaas.monitoring.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import java.util.Comparator;

@Data
@AllArgsConstructor
public class DatabaseInfo implements Comparable<DatabaseInfo> {
    @Getter
    private String name;
    //private Size size;

    @Override
    public int compareTo(DatabaseInfo databaseInfo) {
        return Comparator
                .comparing(DatabaseInfo::getName, Comparator.nullsLast(Comparator.naturalOrder()))
                .compare(this, databaseInfo);
    }
}
