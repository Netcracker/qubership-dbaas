package org.qubership.cloud.dbaas.integration;


import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.qubership.cloud.dbaas.integration.config.PostgresqlContainerResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import static io.restassured.RestAssured.given;
import static jakarta.ws.rs.core.Response.Status.OK;

/**
 * This class tests if docs are presented and build md and confluence docs
 */
@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
class SwaggerOpenApiDocsTest {

    @Test
    void testDocsExistsAndConvertToMarkdown() throws Exception {
        String swaggerJson = given()
                .queryParam("format", "json")
                .when().get("/v3/api-docs")
                .then()
                .statusCode(OK.getStatusCode())
                .extract()
                .asString();
        writeJsonToTarget(swaggerJson);
    }

    private void writeJsonToTarget(String swaggerJson) throws IOException {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        JsonParser jp = new JsonParser();
        JsonElement je = jp.parse(swaggerJson);
        List<String> prettyJsonString = Arrays.asList(gson.toJson(je).split("\n"));
        Files.write(Paths.get("./target/swagger.json"), prettyJsonString);
        Files.write(Paths.get("../docs/OpenAPI.json"), prettyJsonString);
    }
}