package org.qubership.cloud.dbaas.service;

import org.qubership.cloud.dbaas.entity.pg.BgTrack;
import org.qubership.cloud.dbaas.repositories.pg.jpa.BgTrackRepository;
import org.qubership.core.scheduler.po.ProcessDefinition;
import org.qubership.core.scheduler.po.ProcessOrchestrator;
import org.qubership.core.scheduler.po.model.pojo.ProcessInstanceImpl;
import org.qubership.core.scheduler.po.task.TaskState;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

import static org.qubership.cloud.dbaas.Constants.WARMUP_OPERATION;
import static jakarta.transaction.Transactional.TxType.REQUIRES_NEW;

@ApplicationScoped
@Slf4j
public class ProcessService {

    @Inject
    ProcessOrchestrator orchestrator;
    @Inject
    BgTrackRepository bgTrackRepository;

    @Transactional(REQUIRES_NEW)
    public void startProcess(ProcessInstanceImpl processInstance) {
        orchestrator.startProcess(processInstance);
    }

    public ProcessInstanceImpl getProcess(String id) {
        return orchestrator.getProcessInstance(id);
    }

    @Transactional
    public ProcessInstanceImpl createProcess(ProcessDefinition definition, String namespace, String operation) {
        log.info("Create new process");
        Optional<BgTrack> trackOptional = bgTrackRepository.findByNamespaceAndOperation(namespace, operation);
        if (trackOptional.isPresent() && WARMUP_OPERATION.equals(operation)) { //TODO remove && WARMUP_OPERATION.equals(operation) after bulk declarative implementation
            BgTrack track = trackOptional.get();
            log.info("Found process in db: {}", track);
            ProcessInstanceImpl processInstance = orchestrator.getProcessInstance(track.getId());
            if (processInstance != null &&
                    (TaskState.NOT_STARTED.equals(processInstance.getState()) || TaskState.IN_PROGRESS.equals(processInstance.getState()))) {
                log.error("Process {} still running", track.getId());
                return processInstance;
            }
        }

        ProcessInstanceImpl pi = QuarkusTransaction.requiringNew().call(() -> orchestrator.createProcess(definition));
        log.info("New process id: {}", pi.getId());
        if (WARMUP_OPERATION.equals(operation)) {
            BgTrack track = new BgTrack();
            track.setId(pi.getId());
            track.setNamespace(namespace);
            track.setOperation(operation);
            bgTrackRepository.deleteByNamespaceAndOperation(namespace, operation);
            bgTrackRepository.persist(track);
        }
        return pi;
    }

    @Transactional(REQUIRES_NEW)
    public void terminateProcess(String trackingId) {
        orchestrator.terminateProcess(trackingId);
    }

    @Transactional(REQUIRES_NEW)
    public void retryProcess(ProcessInstanceImpl processInstance) {
        orchestrator.retryProcess(processInstance);
    }
}
