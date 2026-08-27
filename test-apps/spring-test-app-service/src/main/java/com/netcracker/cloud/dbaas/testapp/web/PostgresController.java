package com.netcracker.cloud.dbaas.testapp.web;

import com.netcracker.cloud.dbaas.testapp.domain.Item;
import com.netcracker.cloud.dbaas.testapp.repository.ItemRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * PostgreSQL endpoints exercised by the integration test. All DML goes through the
 * DBaaS-resolved datasource; when the connection comes from the mounted secret the service
 * keeps working even if dbaas-aggregator is unreachable.
 */
@RestController
@RequestMapping("/postgres")
public class PostgresController {

    private final ItemRepository repository;

    public PostgresController(ItemRepository repository) {
        this.repository = repository;
    }

    @PostMapping("/items")
    public ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) CreateItemRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name is required"));
        }
        Item created = repository.insert(request.name().trim());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("item", created));
    }

    @GetMapping("/items")
    public Map<String, Object> list() {
        List<Item> items = repository.findAll();
        return Map.of("items", items);
    }

    @DeleteMapping("/items")
    public Map<String, Object> deleteAll() {
        return Map.of("deleted", repository.deleteAll());
    }

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return Map.of("status", "ok", "result", repository.ping());
    }

    @GetMapping("/connection-properties")
    public Map<String, Object> connectionProperties() {
        return repository.connectionInfo();
    }

    public record CreateItemRequest(String name) {
    }
}
