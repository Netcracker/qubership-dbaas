package com.netcracker.cloud.dbaas.dto.backup;

import com.netcracker.cloud.dbaas.entity.pg.backup.DatabasesBackup;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class DeleteResult {

    private DatabasesBackup databasesBackup;
    private Status status = Status.PROCEEDING;
    private String adapterId;
    private String message;

}
