package com.netcracker.it.dbaas.helpers;

import com.netcracker.it.dbaas.entity.composite.CompositeStructureDto;
import lombok.RequiredArgsConstructor;
import okhttp3.Request;

import java.util.Arrays;
import java.util.List;

@RequiredArgsConstructor
public class CompositeStructureHelperV1 {

    private static final String COMPOSITE_STRUCTURES = "api/composite/v1/structures";

    private final DbaasHelperV3 dbaasHelperV3;

    public List<CompositeStructureDto> getCompositeStructures() {
        Request request = dbaasHelperV3.createRequest(COMPOSITE_STRUCTURES,
                dbaasHelperV3.getClusterDbaAuthorization(), null, "GET");
        return Arrays.asList(dbaasHelperV3.executeRequest(request, CompositeStructureDto[].class, 200));
    }

    public CompositeStructureDto getCompositeStructure(String compositeId, boolean mustExist) {
        Request request = dbaasHelperV3.createRequest("%s/%s".formatted(COMPOSITE_STRUCTURES, compositeId),
                dbaasHelperV3.getClusterDbaAuthorization(), null, "GET");
        if (mustExist) {
            return dbaasHelperV3.executeRequest(request, CompositeStructureDto.class, 200);
        }
        return dbaasHelperV3.executeRequest(request, null, 404);
    }

    public void saveCompositeStructure(CompositeStructureDto compositeStructure) {
        saveCompositeStructure(compositeStructure, 204);
    }

    public void saveCompositeStructure(CompositeStructureDto compositeStructure, Integer... expectHttpCode) {
        Request request = dbaasHelperV3.createRequest(COMPOSITE_STRUCTURES,
                dbaasHelperV3.getClusterDbaAuthorization(), compositeStructure, "POST");
        dbaasHelperV3.executeRequest(request, null, expectHttpCode);
    }

    public void deleteCompositeStructure(String compositeId) {
        deleteCompositeStructure(compositeId, 204);
    }

    public void deleteCompositeStructure(String compositeId, Integer... expectHttpCode) {
        Request request = dbaasHelperV3.createRequest("%s/%s/delete".formatted(COMPOSITE_STRUCTURES, compositeId),
                dbaasHelperV3.getClusterDbaAuthorization(), null, "DELETE");
        dbaasHelperV3.executeRequest(request, null, expectHttpCode);
    }

    public void deleteCompositeStructuresWithAutotestNamespaces() {
        getCompositeStructures().stream()
                .filter(structure -> structure.getNamespaces().stream()
                        .anyMatch(CompositeStructureHelperV1::isAutotestNamespace))
                .forEach(structure -> deleteCompositeStructure(structure.getId(), 204, 404));
    }

    private static boolean isAutotestNamespace(String namespace) {
        return DbaasHelperV3.TEST_NAMESPACE_PATTERN.matcher(namespace).matches();
    }
}
