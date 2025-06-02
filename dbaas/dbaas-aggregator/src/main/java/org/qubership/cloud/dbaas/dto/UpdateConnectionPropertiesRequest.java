package org.qubership.cloud.dbaas.dto;

import org.qubership.cloud.dbaas.entity.pg.DbResource;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.SortedMap;

@Data
@Schema(description = "Contains classifier by which a database record for updating connection properties will be found and new connection properties")
public class UpdateConnectionPropertiesRequest {
    @Schema(required = true, description = "Database classifier.")
    SortedMap<String, Object> classifier;

    @Schema(required = true, description = "New connection properties. Structure of connection properties for different db types may be found at https://perch.qubership.org/pages/viewpage.action?spaceKey=CLOUDCORE&title=DbaaS+Adapters")
    Map<String, Object> connectionProperties;

    @Schema(description = "Specifies the new identification of physical database where a logical database is already located. " +
            "You have to pass this parameter if your goal is to update and specify a new physical database." +
            "It is an optional parameter and if not specified then physical database will not change. " +
            "FYI: You can get the list of all physical databases by \"List registered physical databases\" API.")
    private String physicalDatabaseId;


    @Schema(description = "The list of the resources which related to the logical database, for example: user, database." +
            "You should pass this parameter if you change username or database name. In order to update the list you should get an original list, " +
            "change one use and pass. " +
            "FYI: You can get the list of origin database resources by \"List of all databases\" API with \"withResources\" query parameter.",
            ref = "DbResource")
    List<DbResource> resources;

    @Schema(description = "Name of database.")
    private String dbName;
}
