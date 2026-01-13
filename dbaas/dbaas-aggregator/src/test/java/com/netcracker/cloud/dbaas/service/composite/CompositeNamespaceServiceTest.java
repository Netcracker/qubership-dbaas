package com.netcracker.cloud.dbaas.service.composite;

import com.netcracker.cloud.dbaas.dto.composite.CompositeStructureDto;
import com.netcracker.cloud.dbaas.entity.pg.composite.CompositeNamespace;
import com.netcracker.cloud.dbaas.entity.pg.composite.CompositeStructure;
import com.netcracker.cloud.dbaas.repositories.dbaas.CompositeNamespaceDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.dbaas.CompositeNamespaceModifyIndexesDbaasRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompositeNamespaceServiceTest {

    @Mock
    private CompositeNamespaceDbaasRepository compositeNamespaceDbaasRepository;

    @Mock
    private CompositeNamespaceModifyIndexesDbaasRepository compositeNamespaceModifyIndexesDbaasRepository;

    @InjectMocks
    private CompositeNamespaceService compositeNamespaceService;

    @Test
    void testSaveOrUpdateCompositeStructure_Success() {
        CompositeStructureDto compositeRequest = CompositeStructureDto.builder()
                .id("test-id")
                .namespaces(new HashSet<>(Set.of("ns-1", "ns-2")))
                .modifyIndex(BigDecimal.ONE)
                .build();

        assertDoesNotThrow(() -> compositeNamespaceService.saveOrUpdateCompositeStructure(compositeRequest));


        verify(compositeNamespaceDbaasRepository, times(1)).deleteByBaseline("test-id");
        ArgumentCaptor<List<CompositeNamespace>> compositeNamespaceCaptureList = ArgumentCaptor.forClass(List.class);
        verify(compositeNamespaceDbaasRepository, times(1)).saveAll(compositeNamespaceCaptureList.capture());
        List<CompositeNamespace> compositeNamespaceList = compositeNamespaceCaptureList.getAllValues().stream().flatMap(Collection::stream).toList();
        assertEquals(3, compositeNamespaceList.size());
        HashSet<String> expectedNs = new HashSet<>(Arrays.asList("test-id", "ns-1", "ns-2"));
        for (CompositeNamespace compositeNamespace : compositeNamespaceList) {
            assertEquals("test-id", compositeNamespace.getBaseline());
            assertTrue(expectedNs.remove(compositeNamespace.getNamespace()), "list does not contain %s but should".formatted(compositeNamespace.getNamespace()));
        }

        verify(compositeNamespaceModifyIndexesDbaasRepository).findByBaselineName("test-id");
    }

    @Test
    void testGetCompositeStructure_ExistingBaseline() {
        String baseline = "test-id";
        when(compositeNamespaceDbaasRepository.findByBaseline(baseline))
                .thenReturn(List.of(new CompositeNamespace(baseline, "ns-1")));

        Optional<CompositeStructure> result = compositeNamespaceService.getCompositeStructure(baseline);


        assertTrue(result.isPresent());
        assertEquals(baseline, result.get().getBaseline());
        assertEquals(Set.of("ns-1"), result.get().getNamespaces());
    }

    @Test
    void testGetCompositeStructure_NotFound() {
        String baseline = "test-id";
        when(compositeNamespaceDbaasRepository.findByBaseline(baseline))
                .thenReturn(new ArrayList<>());

        Optional<CompositeStructure> result = compositeNamespaceService.getCompositeStructure(baseline);


        assertTrue(result.isEmpty());
    }

    @Test
    void testGetAllCompositeStructures() {
        when(compositeNamespaceDbaasRepository.findAll())
                .thenReturn(List.of(new CompositeNamespace("test-id", "ns-1")));

        List<CompositeStructure> result = compositeNamespaceService.getAllCompositeStructures();

        assertFalse(result.isEmpty());
        assertEquals(1, result.size());
        assertEquals("test-id", result.get(0).getBaseline());
        assertEquals(Set.of("ns-1"), result.get(0).getNamespaces());
    }

    @Test
    void testDeleteCompositeStructure() {
        String baseline = "test-id";

        assertDoesNotThrow(() -> compositeNamespaceService.deleteCompositeStructure(baseline));

        verify(compositeNamespaceDbaasRepository, times(1)).deleteByBaseline(baseline);
    }

    @Test
    void testDeleteNamespace_Baseline() {
        String baseline = "test-id";
        when(compositeNamespaceDbaasRepository.findByBaseline(eq(baseline))).thenReturn(List.of(new CompositeNamespace("test-id", "ns-1")));

        assertDoesNotThrow(() -> compositeNamespaceService.deleteNamespace(baseline));
        verify(compositeNamespaceDbaasRepository, times(1)).deleteByBaseline(baseline);
    }

    @Test
    void testDeleteNamespace_Satellite() {
        String satellite = "ns-1";
        when(compositeNamespaceDbaasRepository.findByBaseline(eq(satellite))).thenReturn(Collections.emptyList());

        assertDoesNotThrow(() -> compositeNamespaceService.deleteNamespace(satellite));
        verify(compositeNamespaceDbaasRepository, times(1)).deleteByNamespace(satellite);
    }

    @Test
    void testGetBaselineByNamespace_ExistingNamespace() {
        String namespace = "ns-1";
        when(compositeNamespaceDbaasRepository.findBaselineByNamespace(namespace))
                .thenReturn(Optional.of(new CompositeNamespace("test-id", namespace)));

        Optional<String> result = compositeNamespaceService.getBaselineByNamespace(namespace);

        // Verification
        assertTrue(result.isPresent());
        assertEquals("test-id", result.get());
    }

}
