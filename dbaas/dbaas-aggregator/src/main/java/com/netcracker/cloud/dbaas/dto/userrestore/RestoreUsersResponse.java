package com.netcracker.cloud.dbaas.dto.userrestore;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class RestoreUsersResponse {
    private List<UnsuccessfulRestore> unsuccessfully;
    private List<SuccessfullRestore> successfully;
}
