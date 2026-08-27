package com.netcracker.cloud.dbaas.testapp.web;

import com.netcracker.cloud.dbaas.testapp.domain.Item;
import com.netcracker.cloud.dbaas.testapp.repository.ItemRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer test of the HTTP contract the integration test relies on, with the data layer mocked.
 * Security filters are disabled so the assertions focus on the controller behaviour.
 */
@WebMvcTest({PostgresController.class, HealthController.class})
@AutoConfigureMockMvc(addFilters = false)
class PostgresControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ItemRepository repository;

    @Test
    void health_returnsOk() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void createItem_returns201WithInsertedName() throws Exception {
        when(repository.insert(eq("widget"))).thenReturn(new Item(1L, "widget", Instant.EPOCH));

        mockMvc.perform(post("/postgres/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"widget\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.item.id").value(1))
                .andExpect(jsonPath("$.item.name").value("widget"));
    }

    @Test
    void createItem_blankName_returns400() throws Exception {
        mockMvc.perform(post("/postgres/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listItems_returnsItemsArray() throws Exception {
        when(repository.findAll()).thenReturn(List.of(new Item(7L, "widget", Instant.EPOCH)));

        mockMvc.perform(get("/postgres/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("widget"));
    }

    @Test
    void deleteItems_returnsDeletedCount() throws Exception {
        when(repository.deleteAll()).thenReturn(3);

        mockMvc.perform(delete("/postgres/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(3));
    }
}
