package org.qubership.cloud.dbaas.service;

import org.qubership.cloud.dbaas.entity.pg.BgTrack;
import org.qubership.cloud.dbaas.repositories.pg.jpa.BgTrackRepository;
import org.qubership.core.scheduler.po.ProcessDefinition;
import org.qubership.core.scheduler.po.ProcessOrchestrator;
import org.qubership.core.scheduler.po.model.pojo.ProcessInstanceImpl;
import org.qubership.core.scheduler.po.task.TaskState;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.TransactionRunnerOptions;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.qubership.cloud.dbaas.Constants.WARMUP_OPERATION;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProcessServiceTest {

    private static MockedStatic<QuarkusTransaction> mockedStatic;

    @InjectMocks
    ProcessService processService;
    @Mock
    ProcessOrchestrator orchestrator;
    @Mock
    BgTrackRepository bgTrackRepository;

    @BeforeAll
    static void beforeAll() {
        mockedStatic = mockStatic(QuarkusTransaction.class);
        TransactionRunnerOptions txRunner = mock(TransactionRunnerOptions.class);
        when(txRunner.call(any())).then(invocationOnMock -> ((Callable) invocationOnMock.getArgument(0)).call());
        mockedStatic.when(QuarkusTransaction::requiringNew).thenReturn(txRunner);
    }

    @AfterAll
    static void afterAll() {
        mockedStatic.close();
    }

    @Test
    void startProcess() {
        ProcessInstanceImpl testProcessInstance = mock(ProcessInstanceImpl.class);
        processService.startProcess(testProcessInstance);
        verify(orchestrator).startProcess(testProcessInstance);
    }

    @Test
    void getProcess() {
        ProcessInstanceImpl testProcessInstance = mock(ProcessInstanceImpl.class);
        String poID = testProcessInstance.getId();
        when(orchestrator.getProcessInstance(poID)).thenReturn(testProcessInstance);
        ProcessInstanceImpl returnedProcess = processService.getProcess(poID);
        Assertions.assertEquals(testProcessInstance, returnedProcess);
    }

    @Test
    void testTerminateProcess() {
        ProcessInstanceImpl testProcessInstance = mock(ProcessInstanceImpl.class);
        when(testProcessInstance.getId()).thenReturn(UUID.randomUUID().toString());
        processService.terminateProcess(testProcessInstance.getId());
        verify(orchestrator).terminateProcess(testProcessInstance.getId());
    }

    @Test
    void testRetryProcess() {
        ProcessInstanceImpl testProcessInstance = mock(ProcessInstanceImpl.class);
        processService.retryProcess(testProcessInstance);
        verify(orchestrator).retryProcess(testProcessInstance);
    }

    @Test
    void createAlreadyExistsProcess() {
        String operation = WARMUP_OPERATION;
        String namespace = "test-ns";
        ProcessDefinition processDefinition = new ProcessDefinition("process");

        ProcessInstanceImpl testProcessInstance = mock(ProcessInstanceImpl.class);
        when(testProcessInstance.getState()).thenReturn(TaskState.IN_PROGRESS);

        BgTrack track = new BgTrack();
        String trackId = UUID.randomUUID().toString();
        track.setId(trackId);
        when(bgTrackRepository.findByNamespaceAndOperation(namespace, operation)).thenReturn(Optional.of(track));

        when(orchestrator.getProcessInstance(trackId)).thenReturn(testProcessInstance);
        ProcessInstanceImpl process = processService.createProcess(processDefinition, namespace, operation);
        Assertions.assertEquals(testProcessInstance, process);
    }

    @Test
    void createProcess() {
        String operation = WARMUP_OPERATION;
        String namespace = "test-ns";
        ProcessDefinition processDefinition = new ProcessDefinition("process");

        ProcessInstanceImpl testProcessInstance = mock(ProcessInstanceImpl.class);
        when(testProcessInstance.getId()).thenReturn(UUID.randomUUID().toString());

        BgTrack track = new BgTrack();
        String trackId = UUID.randomUUID().toString();
        track.setId(trackId);

        when(orchestrator.createProcess(processDefinition)).thenReturn(testProcessInstance);
        ProcessInstanceImpl process = processService.createProcess(processDefinition, namespace, operation);
        Assertions.assertEquals(testProcessInstance, process);
        verify(bgTrackRepository).deleteByNamespaceAndOperation(namespace, operation);
        verify(bgTrackRepository).persist(any(BgTrack.class));
    }
}