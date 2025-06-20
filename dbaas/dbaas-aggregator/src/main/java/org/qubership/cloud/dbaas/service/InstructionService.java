package org.qubership.cloud.dbaas.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.qubership.cloud.dbaas.dto.InstructionType;
import org.qubership.cloud.dbaas.dto.v3.AdditionalRoles;
import org.qubership.cloud.dbaas.dto.v3.Instruction;
import org.qubership.cloud.dbaas.dto.v3.PhysicalDatabaseRegistryRequestV3;
import org.qubership.cloud.dbaas.dto.v3.SuccessRegistrationV3;
import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.PhysicalDatabase;
import org.qubership.cloud.dbaas.entity.pg.PhysicalDatabaseInstruction;
import org.qubership.cloud.dbaas.repositories.pg.jpa.PhysicalDatabaseInstructionRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
@Slf4j
public class InstructionService {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final int PORTION_SIZE = 100;

    @Inject
    PhysicalDatabasesService physicalDatabasesService;
    @Inject
    DBaaService dBaaService;
    @Inject
    PhysicalDatabaseInstructionRepository physicalDatabaseInstructionRepository;

    public Boolean isRolesDifferent(List<String> recievedRoles, List<String> currentSupportedRoles) {
        recievedRoles.replaceAll(String::toLowerCase);
        //check that additional roles exist
        Collections.sort(recievedRoles);
        Collections.sort(currentSupportedRoles);
        return !currentSupportedRoles.equals(recievedRoles);
    }

    public List<Database> getLogicalDatabasesForMigration(String phydbid, String type, List<String> roles) {
        List<Database> databases = physicalDatabasesService.getDatabasesByPhysDbAndType(phydbid, type);
        List<Database> databasesForMigration = new ArrayList<>();
        for (Database database : databases) {
            if (database.isMarkedForDrop()) continue;
            Set<String> supportedRoles = database.getConnectionProperties()
                    .stream()
                    .map(stringObjectMap -> (String) stringObjectMap.get("role"))
                    .collect(Collectors.toSet());

            for (String role : roles) {
                if (!supportedRoles.contains(role)) {
                    databasesForMigration.add(database);
                    break;
                }
            }
        }
        return databasesForMigration;
    }

    public Instruction buildInstructionForAdditionalRoles(List<Database> logicalDatabases) {
        Instruction instruction = new Instruction();
        instruction.setId(UUID.randomUUID().toString());
        List<AdditionalRoles> additionalRolesList = new ArrayList<>();
        for (Database database : logicalDatabases) {
            AdditionalRoles additionalRoles = new AdditionalRoles();
            additionalRoles.setId(database.getId());
            additionalRoles.setConnectionProperties(database.getConnectionProperties());
            additionalRoles.setResources(database.getResources());
            additionalRoles.setDbName(database.getName());
            additionalRolesList.add(additionalRoles);
        }
        instruction.setAdditionalRoles(additionalRolesList);
        return instruction;
    }

    public Map<String, Object> saveInstruction(PhysicalDatabase database, Instruction instruction, PhysicalDatabaseRegistryRequestV3 parameters) throws JsonProcessingException {
        for (AdditionalRoles additionalRole : instruction.getAdditionalRoles()) {
            for (Map<String, Object> connectionPropertiesForRole : additionalRole.getConnectionProperties()) {
                connectionPropertiesForRole.put("role", connectionPropertiesForRole.get("role").toString().toLowerCase());
            }
        }
        saveInstructionIntoDB(instruction, database, parameters);

        //find portion from database
        Instruction response = findPortion(instruction.getId());

        Map<String, Object> responseBody = new HashMap<>();
        responseBody.put("instruction", response);
        return responseBody;
    }


    public Instruction findPortion(String instructionId) throws JsonProcessingException {
        log.info("finding instruction for instruction id = {}", instructionId);
        Optional<PhysicalDatabaseInstruction> physicalDatabaseInstruction = physicalDatabaseInstructionRepository
                .findByIdOptional(UUID.fromString(instructionId));
        log.info("Got instruction with all roles by id with id = {}", physicalDatabaseInstruction);
        if (physicalDatabaseInstruction.isPresent()) {
            List<AdditionalRoles> additionalRolesList = convertStringToList(physicalDatabaseInstruction.get().getContext());
            if (!additionalRolesList.isEmpty()) {
                List<AdditionalRoles> listWithPortion = new ArrayList<>();
                for (int i = 0; i < additionalRolesList.size() && i < PORTION_SIZE; i++) {
                    listWithPortion.add(additionalRolesList.get(i));
                }
                log.info("Find next connectionProperties portion in instruction with id = {}", physicalDatabaseInstruction.get().getId());
                return new Instruction(physicalDatabaseInstruction.get().getId().toString(), listWithPortion);
            }
            log.info("No more connectionProperties portions in instruction with id = {}", physicalDatabaseInstruction.get().getId());
            return null;
        }
        log.info("No instruction found with id = {}", instructionId);
        return null;
    }

    private List<AdditionalRoles> convertStringToList(String context) throws JsonProcessingException {
        TypeReference<List<AdditionalRoles>> mapType = new TypeReference<List<AdditionalRoles>>() {
        };
        return objectMapper.readValue(context, mapType);
    }

    public Instruction findInstructionById(String id) throws JsonProcessingException {
        Instruction instruction = new Instruction();
        Optional<PhysicalDatabaseInstruction> physicalDatabaseInstruction = physicalDatabaseInstructionRepository.findByIdOptional(UUID.fromString(id));
        if (physicalDatabaseInstruction.isPresent()) {
            instruction.setId(String.valueOf(physicalDatabaseInstruction.get().getId()));
            instruction.setAdditionalRoles(convertStringToList(physicalDatabaseInstruction.get().getContext()));
        } else {
            log.info("No instruction found from  PhysicalDatabaseInstruction with id = {}", instruction.getId());
        }
        return instruction;
    }

    public List<AdditionalRoles> findNextAdditionalRoles(String instructionId) throws JsonProcessingException {
        Instruction responseInstruction = findPortion(instructionId);
        if (responseInstruction != null) return responseInstruction.getAdditionalRoles();
        return null;
    }

    public void completeMigrationProcedure(String phydbid, String instructionId, Instruction currentInstruction) {
        log.info("Saving supported roles in physical database with id = {}", phydbid);
        Optional<PhysicalDatabaseInstruction> physicalDatabaseInstruction = physicalDatabaseInstructionRepository.findByIdOptional(UUID.fromString(instructionId));
        if (physicalDatabaseInstruction.isPresent()) {
            physicalDatabasesService.savePhysicalDatabaseWithRoles(phydbid, physicalDatabaseInstruction.get().getPhysicalDbRegRequest());
            log.info("Removing instruction after saving roles by id = {}", currentInstruction.getId());
            deleteInstruction(currentInstruction.getId());
        }
    }

    public PhysicalDatabaseInstruction saveInstructionIntoDB(Instruction instruction, PhysicalDatabase database, PhysicalDatabaseRegistryRequestV3 parameters) throws JsonProcessingException {
        PhysicalDatabaseInstruction physicalDatabaseInstruction = new PhysicalDatabaseInstruction();
        physicalDatabaseInstruction.setId(UUID.fromString(instruction.getId()));
        physicalDatabaseInstruction.setPhysicalDatabaseId(database.getPhysicalDatabaseIdentifier());
        physicalDatabaseInstruction.setContext(listToString(instruction.getAdditionalRoles()));
        physicalDatabaseInstruction.setPhysicalDbRegRequest(parameters);
        physicalDatabaseInstruction.setInstructionType(InstructionType.MULTIUSERS_MIGRATION);
        Date timeDbInstructionCreation = new Date();
        physicalDatabaseInstruction.setTimeCreation(timeDbInstructionCreation);

        log.info("Saving instruction");
        physicalDatabaseInstructionRepository.persist(physicalDatabaseInstruction);
        log.info("PhysicalDatabaseInstruction saved with instruction");
        return physicalDatabaseInstruction;
    }

    public String listToString(List<AdditionalRoles> additionalRoles) throws JsonProcessingException {
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        return objectMapper.writeValueAsString(additionalRoles);
    }

    public void deleteInstruction(String instructionId) {
        Optional<PhysicalDatabaseInstruction> physicalDatabaseInstruction = physicalDatabaseInstructionRepository.findByIdOptional(UUID.fromString(instructionId));
        if (physicalDatabaseInstruction.isPresent()) {
            log.info("Deleting instruction data from physicalDatabaseInstruction = {}", instructionId);
            physicalDatabaseInstructionRepository.deleteById(physicalDatabaseInstruction.get().getId());
        } else {
            log.info("no instruction found in physicalDatabaseInstruction with id = {}", instructionId);
        }
    }

    public PhysicalDatabaseInstruction findInstructionByPhyDbId(String physicalDatabaseIdentifier) {
        return physicalDatabaseInstructionRepository.findByPhysicalDatabaseId(physicalDatabaseIdentifier);
    }

    public void saveConnectionPropertiesAfterRolesRegistration(List<SuccessRegistrationV3> successRegistrationList) {
        for (SuccessRegistrationV3 successRegistration : successRegistrationList) {
            dBaaService.updateDatabaseConnectionPropertiesAndResourcesById(successRegistration.getId(),
                    successRegistration.getConnectionProperties(), successRegistration.getResources());
        }
        log.info("All processed logical databases successfully saved");
    }

    public void updateInstructionWithContext(Instruction currentInstruction, List<AdditionalRoles> finalrolesToset) throws JsonProcessingException {
        Optional<PhysicalDatabaseInstruction> existingDatabaseInstruction = physicalDatabaseInstructionRepository.findByIdOptional(UUID.fromString(currentInstruction.getId()));
        if (existingDatabaseInstruction.isPresent()) {
            existingDatabaseInstruction.get().setContext(listToString(finalrolesToset));
            physicalDatabaseInstructionRepository.persist(existingDatabaseInstruction.get());
            log.info("PhysicalDatabaseInstruction updated with context");
        }
    }
}
