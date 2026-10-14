package com.netcracker.cloud.dbaas.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.netcracker.cloud.dbaas.dto.InstructionType;
import com.netcracker.cloud.dbaas.dto.v3.AdditionalRoles;
import com.netcracker.cloud.dbaas.dto.v3.Instruction;
import com.netcracker.cloud.dbaas.dto.v3.PhysicalDatabaseRegistryRequestV3;
import com.netcracker.cloud.dbaas.dto.v3.SuccessRegistrationV3;
import com.netcracker.cloud.dbaas.entity.pg.Database;
import com.netcracker.cloud.dbaas.entity.pg.PhysicalDatabase;
import com.netcracker.cloud.dbaas.entity.pg.PhysicalDatabaseInstruction;
import com.netcracker.cloud.dbaas.repositories.pg.jpa.PhysicalDatabaseInstructionRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@ApplicationScoped
@Slf4j
public class InstructionService {

    public static final int PORTION_SIZE = 100;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Inject
    PhysicalDatabasesService physicalDatabasesService;
    @Inject
    DBaaService dBaaService;
    @Inject
    PhysicalDatabaseInstructionRepository physicalDatabaseInstructionRepository;
    @Inject
    AsyncOperations asyncOperations;
    @Inject
    LockService lockService;

    public Boolean isRolesDifferent(List<String> recievedRoles, List<String> currentSupportedRoles) {
        recievedRoles.replaceAll(String::toLowerCase);
        //check that additional roles exist
        Collections.sort(recievedRoles);
        Collections.sort(currentSupportedRoles);
        return !currentSupportedRoles.equals(recievedRoles);
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
            PhysicalDatabaseInstruction physicalDatabaseInstruction1 = physicalDatabaseInstruction.get();
            Instruction instruction = getNextPortion(physicalDatabaseInstruction1.getId().toString(), physicalDatabaseInstruction1.getContext());

            if (instruction == null) {
                log.info("No more connectionProperties portions in instruction with id = {}", physicalDatabaseInstruction.get().getId());
                return null;
            }
            return instruction;
        }
        log.info("No instruction found with id = {}", instructionId);
        return null;
    }

    public static List<AdditionalRoles> convertStringToList(String context) throws JsonProcessingException {
        TypeReference<List<AdditionalRoles>> mapType = new TypeReference<List<AdditionalRoles>>() {
        };
        return objectMapper.readValue(context, mapType);
    }

    public static List<AdditionalRoles> getPortion(List<AdditionalRoles> additionalRoles) {
        List<AdditionalRoles> listWithPortion = new ArrayList<>();
        for (int i = 0; i < additionalRoles.size() && i < PORTION_SIZE; i++) {
            listWithPortion.add(additionalRoles.get(i));
        }
        return listWithPortion;
    }

    public static Instruction getNextPortion(String instructionId, String context) throws JsonProcessingException {
        List<AdditionalRoles> additionalRolesList = convertStringToList(context);
        if (!additionalRolesList.isEmpty()) {
            List<AdditionalRoles> listWithPortion = getPortion(additionalRolesList);
            log.info("Find next connectionProperties portion in instruction with id = {}", instructionId);
            return new Instruction(instructionId, listWithPortion);
        }
        return null;
    }

    public Instruction findInstructionById(String id) throws JsonProcessingException {
        Optional<PhysicalDatabaseInstruction> physicalDatabaseInstruction = physicalDatabaseInstructionRepository.findByIdOptional(UUID.fromString(id));
        if (physicalDatabaseInstruction.isEmpty()) {
            log.info("No instruction found from PhysicalDatabaseInstruction with id = {}", id);
            return null;
        }
        Instruction instruction = new Instruction();
        instruction.setId(String.valueOf(physicalDatabaseInstruction.get().getId()));
        instruction.setAdditionalRoles(convertStringToList(physicalDatabaseInstruction.get().getContext()));
        return instruction;
    }

    public List<AdditionalRoles> findNextAdditionalRoles(String instructionId) throws JsonProcessingException {
        Instruction responseInstruction = findPortion(instructionId);
        if (responseInstruction != null) return responseInstruction.getAdditionalRoles();
        return null;
    }

    public void completeMigrationProcedure(String phydbid, String instructionId) {
        log.info("Saving supported roles in physical database with id = {}", phydbid);
        Optional<PhysicalDatabaseInstruction> physicalDatabaseInstruction = physicalDatabaseInstructionRepository.findByIdOptional(UUID.fromString(instructionId));
        if (physicalDatabaseInstruction.isPresent()) {
            physicalDatabasesService.savePhysicalDatabaseWithRoles(phydbid, physicalDatabaseInstruction.get().getPhysicalDbRegRequest());
            log.info("Removing instruction after saving roles by id = {}", instructionId);
            deleteInstruction(instructionId);
        }
    }

    public PhysicalDatabaseInstruction saveInstructionIntoDB(Instruction instruction, PhysicalDatabase database, PhysicalDatabaseRegistryRequestV3 parameters) throws JsonProcessingException {
        return saveInstructionIntoDB(UUID.fromString(instruction.getId()), instruction.getAdditionalRoles(), database.getPhysicalDatabaseIdentifier(), parameters);
    }

    private PhysicalDatabaseInstruction saveInstructionIntoDB(UUID instructionId, List<AdditionalRoles> additionalRoles, String physicalDatabaseId, PhysicalDatabaseRegistryRequestV3 parameters) throws JsonProcessingException {
        PhysicalDatabaseInstruction physicalDatabaseInstruction = new PhysicalDatabaseInstruction();
        physicalDatabaseInstruction.setId(instructionId);
        physicalDatabaseInstruction.setPhysicalDatabaseId(physicalDatabaseId);
        physicalDatabaseInstruction.setContext(listToString(additionalRoles));
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
            updateInstructionWithContext(existingDatabaseInstruction.get(), finalrolesToset);
        }
    }

    public void updateInstructionWithContext(PhysicalDatabaseInstruction physicalDatabaseInstruction, List<AdditionalRoles> finalRolesToSet) throws JsonProcessingException {
        physicalDatabaseInstruction.setContext(listToString(finalRolesToSet));
        physicalDatabaseInstructionRepository.save(physicalDatabaseInstruction);
        log.info("PhysicalDatabaseInstruction updated with context");
    }

    public List<AdditionalRoles> getFinalInstructionToSet(List<SuccessRegistrationV3> successRegistrationV3s, Instruction instruction) {
        return getFinalInstructionToSet(successRegistrationV3s, instruction.getAdditionalRoles());
    }

    public List<AdditionalRoles> getFinalInstructionToSet(List<SuccessRegistrationV3> successRegistrationV3s, List<AdditionalRoles> additionalRoles) {
        List<UUID> dbIdList = new ArrayList<>();

        for (SuccessRegistrationV3 successRegistration : successRegistrationV3s) {
            dbIdList.add(successRegistration.getId());
        }

        return additionalRoles.stream()
                .filter(role -> !dbIdList.contains(role.getId()))
                .toList();
    }


}
