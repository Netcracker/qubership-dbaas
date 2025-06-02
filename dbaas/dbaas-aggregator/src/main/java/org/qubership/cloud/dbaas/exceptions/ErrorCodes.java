package org.qubership.cloud.dbaas.exceptions;

import org.qubership.cloud.core.error.runtime.ErrorCode;

public enum ErrorCodes implements ErrorCode {
    CORE_DBAAS_2000(
            "CORE-DBAAS-2000",
            "Unexpected exception",
            "Unexpected exception"),
    CORE_DBAAS_4000(
            "CORE-DBAAS-4000",
            "Requested physical database is not registered",
            "Requested physical database is not registered. %s"),
    CORE_DBAAS_4001(
            "CORE-DBAAS-4001",
            "Invalid database backup request",
            "Invalid database backup request. %s"),
    CORE_DBAAS_4002(
            "CORE-DBAAS-4002",
            "Conflict database request",
            "Conflict database request. %s"),
    CORE_DBAAS_4003(
            "CORE-DBAAS-4003",
            "Deleting logical databases and balancing rules is allowed only in non prod mode",
            "Deleting logical databases and balancing rules is allowed only in non prod mode"),
    CORE_DBAAS_4004(
            "CORE-DBAAS-4004",
            "Namespace from request is not equal to one from database classifier",
            "Namespace from request '%s' is not equal to one from database classifier '%s'.%s"),
    CORE_DBAAS_4005(
            "CORE-DBAAS-4005",
            "Invalid DB Create request",
            "Invalid DB Create request. %s"),
    CORE_DBAAS_4006(
            "CORE-DBAAS-4006",
            "DB not found",
            "No database of type: '%s' with classifier: %s"),
    CORE_DBAAS_4007(
            "CORE-DBAAS-4007",
            "Invalid Password change request",
            "Invalid Password change request. %s"),
    CORE_DBAAS_4008(
            "CORE-DBAAS-4008",
            "Balancing rule was not created/updated as there is conflicting rule with same order and database type",
            "Balancing rule was not created/updated as another rule with database type '%s' and order '%d' already exists. " +
                    "Conflicts with rule: '%s'"),
    CORE_DBAAS_4009(
            "CORE-DBAAS-4009",
            "Password change operation failed",
            "Password change operation failed. See details in '/meta/response'"),
    CORE_DBAAS_4010(
            "CORE-DBAAS-4010",
            "Invalid classifier",
            "Invalid classifier. %s. Classifier: %s"),
    CORE_DBAAS_4011(
            "CORE-DBAAS-4011",
            "Invalid physical identifier",
            "Invalid physical identifier. %s"),
    CORE_DBAAS_4012(
            "CORE-DBAAS-4012",
            "Backup not found",
            "No backup with id: '%s' is found"),
    CORE_DBAAS_4013(
            "CORE-DBAAS-4013",
            "Deleting target backup is not allowed",
            "Deleting target backup is not allowed. %s"),
    CORE_DBAAS_4014(
            "CORE-DBAAS-4014",
            "Failed to delete backup",
            "Failed to delete backup %s. Failed %d subdeletion"),
    CORE_DBAAS_4015(
            "CORE-DBAAS-4015",
            "Restoration not found",
            "No restoration with id: '%s' is found"),
    CORE_DBAAS_4016(
            "CORE-DBAAS-4016",
            "The request body must contain at least one microserviceName",
            "The request body must contain at least one microserviceName"),
    CORE_DBAAS_4017(
            "CORE-DBAAS-4017",
            "Request to adapter failed",
            "Request to adapter failed. Status code: %d"),
    CORE_DBAAS_4018(
            "CORE-DBAAS-4018",
            "Backup execution failed",
            "Backup execution failed. %s"),
    CORE_DBAAS_4019(
            "CORE-DBAAS-4019",
            "Failed to re-create databases",
            "Failed to re-create databases in namespace '%s'"),
    CORE_DBAAS_4020(
            "CORE-DBAAS-4020",
            "Invalid update connection properties request",
            "Invalid connection properties request: %s."),
    CORE_DBAAS_4021(
            "CORE-DBAAS-4021",
            "Connection properties for role does not exist in database",
            "Database does not contain connection properties for specified role: %s."),
    CORE_DBAAS_4022(
            "CORE-DBAAS-4022",
            "The request body must contain origin service",
            "The request body must contain origin service"),
    CORE_DBAAS_4023(
            "CORE-DBAAS-4023",
            "Requested role is not allowed by service",
            "Requested role is not allowed = %s"),
    CORE_DBAAS_4024(
            "CORE-DBAAS-4024",
            "Connection properties doesn't contain field 'role'",
            "Database with calssifier == %s. Connection properties of this database doesn't contain field 'role'."),
    CORE_DBAAS_4025(
            "CORE-DBAAS-4025",
            "Connection properties must not be null or empty",
            "Connection properties must not be null or empty"),
    CORE_DBAAS_4026(
            "CORE-DBAAS-4026",
            "Create request must not contain username, password, dbName or InitScriptIdentifiers when used V2 adapter version",
            "Create request= %s must not contain username, password or dbName when used V2 adapter version"),

    CORE_DBAAS_4027(
            "CORE-DBAAS-4027",
            "Register request does not contain required fields",
            "registered database must contain %s"),

    CORE_DBAAS_4028(
            "CORE-DBAAS-4028",
            "DbHost name has wrong format",
            "register request contains dbHost field, but it has wrong format: %s." +
                    "Must be in format: <service-name>.<namesapce>, e.g.: pg-patroni.postgresql-core"),

    CORE_DBAAS_4029(
            "CORE-DBAAS-4029",
            "Logical database can't be found",
            "Logical database can't be found. Error message: %s"),

    CORE_DBAAS_4030(
            "CORE-DBAAS-4030",
            "Backup request have not been passed validate procedure",
            "The backup request have not been passed validate procedure and some adapters do not supported backup operation: %s."
    ),
    CORE_DBAAS_4031(
            "CORE-DBAAS-4031",
            "Requested user is not found",
            "Requested user is not found. Request body = %s"
    ),
    CORE_DBAAS_4032(
            "CORE-DBAAS-4032",
            "Error during deletion from adapter",
            "Error during deletion from adapter. Request body = %s"
    ),
    CORE_DBAAS_4033(
            "CORE-DBAAS-4033",
            "No balancing rule",
            "No balancing rule exists to determine physical database for microservice = %s and namespace = %s"
    ),
    CORE_DBAAS_4034(
            "CORE-DBAAS-4034",
            "Duplicate balancing rule",
            "Duplicate balancing rule found. Error message: %s"
    ),

    CORE_DBAAS_4035(
            "CORE-DBAAS-4035",
            "Unknown declarative configuration kind",
            "Received unknown declarative configuration kind = %s. Supported kinds are 'DatabaseDeclaration' and 'DbPolicy'"),

    CORE_DBAAS_4036(
            "CORE-DBAAS-4036",
            "Declarative configuration request validation failed",
            "Declarative configuration request validation failed. Error: %s"),

    CORE_DBAAS_4037(
            "CORE-DBAAS-4037",
            "Init domain request validation error",
            ""),

    CORE_DBAAS_4038(
            "CORE-DBAAS-4038",
            "Request body contains invalid settings state",
            "%s"),
    CORE_DBAAS_4039(
            "CORE-DBAAS-4039",
            "Blue-green domain is not exist",
            "%s"),
    CORE_DBAAS_4040(
            "CORE-DBAAS-4040",
            "Blue-green namespace is not empty",
            "%s"),
    CORE_DBAAS_4041(
            "CORE-DBAAS-4041",
            "Can't find database in active namespace",
            "%s"),
    CORE_DBAAS_4042(
            "CORE-DBAAS-4042",
            "Can't interact with not versioned db",
            "%s"),
    CORE_DBAAS_4043(
            "CORE-DBAAS-4043",
            "Request does not contain required fields",
            "request must contain %s"),
    CORE_DBAAS_4044(
            "CORE-DBAAS-4044",
            "Invalid link databases request",
            "Invalid link databases request: %s."),


    CORE_DBAAS_7002(
            "CORE-DBAAS-7002",
            "trackingId not found",
            "No operations with trackingId: '%s' is found"),

    CORE_DBAAS_7003(
            "CORE-DBAAS-7003",
            "Invalid composite structure request",
            "Validation error: '%s'");

    private final String code;
    private final String title;
    private final String template;

    ErrorCodes(String code, String title, String template) {
        this.code = code;
        this.title = title;
        this.template = template;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getTitle() {
        return title;
    }

    public String getDetail(Object... args) {
        return template != null ? String.format(template, args) : getTitle();
    }
}
