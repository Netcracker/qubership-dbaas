package com.netcracker.cloud.dbaas.service;

import com.netcracker.cloud.dbaas.entity.configProperty.RotationNotificationProperty;
import com.netcracker.cloud.dbaas.entity.dto.RotationEventPayload;
import com.netcracker.cloud.dbaas.entity.pg.OperatorEvent;
import com.netcracker.cloud.dbaas.enums.OperatorEventType;
import com.netcracker.cloud.dbaas.repositories.pg.jpa.OperatorEventRepository;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.netcracker.cloud.dbaas.enums.OperatorEventStatus.FAILED;
import static com.netcracker.cloud.dbaas.enums.OperatorEventStatus.PENDING;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.eq;

@ExtendWith(MockitoExtension.class)
class OperatorEventDispatcherTest {

    @Mock
    RotationNotificationProperty rotationProperty;
    @Mock
    OperatorEventRepository operatorEventRepository;
    @Mock
    OperatorWebhook operatorWebhook;
    @Mock
    OperatorEventStatusUpdater statusUpdater;

    OperatorEventDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new OperatorEventDispatcher(
                rotationProperty, operatorEventRepository, operatorWebhook, statusUpdater);
    }

    // ---- helpers -------------------------------------------------------

    private OperatorEvent pendingEvent() {
        OperatorEvent e = new OperatorEvent();
        e.setId(UUID.randomUUID());
        e.setEventType(OperatorEventType.ROTATION_OCCURRED);
        e.setPayload(new RotationEventPayload());
        e.setStatus(PENDING);
        e.setAttempts(0);
        e.setNextAttemptAt(OffsetDateTime.now());
        return e;
    }

    /** Stub notify() to throw only for a specific payload instance (identity, not equals).
     *  Lenient: prevents PotentialStubbingProblem when other events' payloads don't match. */
    private void throwOnNotify(OperatorEvent event, Throwable t) {
        RotationEventPayload payload = event.getPayload();
        lenient().doThrow(t).when(operatorWebhook).notify(argThat(p -> p == payload));
    }

    private void enableDispatcher() {
        when(rotationProperty.enabled()).thenReturn(true);
        when(rotationProperty.callbackUrl()).thenReturn("http://operator/webhook");
    }

    private WebApplicationException waeWithStatus(int status) {
        Response r = mock(Response.class);
        Response.StatusType statusType = mock(Response.StatusType.class);
        when(statusType.getStatusCode()).thenReturn(status);
        when(r.getStatusInfo()).thenReturn(statusType);
        when(r.getStatus()).thenReturn(status);
        when(r.readEntity(String.class)).thenReturn("error " + status);
        return new WebApplicationException(r);
    }

    // ---- guard: early-exit conditions ----------------------------------

    @Test
    void dispatch_skipsWhenDisabled() {
        when(rotationProperty.enabled()).thenReturn(false);

        dispatcher.dispatch();

        verifyNoInteractions(operatorEventRepository, operatorWebhook, statusUpdater);
    }

    @Test
    void dispatch_skipsWhenCallbackUrlBlank() {
        when(rotationProperty.enabled()).thenReturn(true);
        when(rotationProperty.callbackUrl()).thenReturn("   ");

        dispatcher.dispatch();

        verifyNoInteractions(operatorEventRepository, operatorWebhook, statusUpdater);
    }

    @Test
    void dispatch_skipsWhenBatchEmpty() {
        enableDispatcher();
        when(operatorEventRepository.claimPendingBatch(anyInt(), any())).thenReturn(List.of());

        dispatcher.dispatch();

        verifyNoInteractions(operatorWebhook, statusUpdater);
    }

    // ---- success -------------------------------------------------------

    @Test
    void dispatch_success_callsMarkSent() {
        enableDispatcher();
        OperatorEvent event = pendingEvent();
        when(operatorEventRepository.claimPendingBatch(anyInt(), any())).thenReturn(List.of(event));

        dispatcher.dispatch();

        verify(statusUpdater).markSent(event.getId());
        verify(statusUpdater, never()).markFailed(any(), any());
        verify(statusUpdater, never()).markRetry(any(), any());
    }

    // ---- 4xx: immediate permanent failure, no retry --------------------

    @Test
    void dispatch_4xxError_callsMarkFailed() {
        enableDispatcher();
        OperatorEvent event = pendingEvent();
        when(operatorEventRepository.claimPendingBatch(anyInt(), any())).thenReturn(List.of(event));
        doThrow(waeWithStatus(422)).when(operatorWebhook).notify(any());

        dispatcher.dispatch();

        verify(statusUpdater).markFailed(eq(event.getId()), any());
        verify(statusUpdater, never()).markSent(any());
        verify(statusUpdater, never()).markRetry(any(), any());
    }

    // ---- 5xx: routed to markRetry -------------------------------------

    @Test
    void dispatch_5xxError_callsMarkRetry() {
        enableDispatcher();
        OperatorEvent event = pendingEvent();
        when(operatorEventRepository.claimPendingBatch(anyInt(), any())).thenReturn(List.of(event));
        doThrow(waeWithStatus(503)).when(operatorWebhook).notify(any());

        dispatcher.dispatch();

        verify(statusUpdater).markRetry(eq(event.getId()), any());
        verify(statusUpdater, never()).markSent(any());
        verify(statusUpdater, never()).markFailed(any(), any());
    }

    // ---- generic (non-WAE) exception: routed to markRetry --------------

    @Test
    void dispatch_genericException_callsMarkRetry() {
        enableDispatcher();
        OperatorEvent event = pendingEvent();
        when(operatorEventRepository.claimPendingBatch(anyInt(), any())).thenReturn(List.of(event));
        doThrow(new RuntimeException("connection refused")).when(operatorWebhook).notify(any());

        dispatcher.dispatch();

        verify(statusUpdater).markRetry(eq(event.getId()), any());
        verify(statusUpdater, never()).markSent(any());
        verify(statusUpdater, never()).markFailed(any(), any());
    }

    // ---- retry exhaustion: notify called exactly maxAttempts times -----

    @Test
    void dispatch_notifyCalledExactlyMaxAttemptsTimes() {
        int maxAttempts = 10;
        enableDispatcher();
        OperatorEvent event = pendingEvent();

        // real repo behaviour: only claims the event while it is still PENDING
        when(operatorEventRepository.claimPendingBatch(anyInt(), any()))
                .thenAnswer(inv -> event.getStatus() == PENDING ? List.of(event) : List.of());

        // simulates real OperatorEventStatusUpdater: stamp + exhaust check
        doAnswer(inv -> {
            event.increaseAttempt();
            if (event.getAttempts() >= maxAttempts) {
                event.setStatus(FAILED);
            }
            return null;
        }).when(statusUpdater).markRetry(eq(event.getId()), any());

        doThrow(waeWithStatus(503)).when(operatorWebhook).notify(any());

        for (int i = 0; i < maxAttempts + 5; i++) {
            dispatcher.dispatch();
        }

        verify(operatorWebhook, times(maxAttempts)).notify(any());
        verify(statusUpdater, times(maxAttempts)).markRetry(eq(event.getId()), any());
        verify(statusUpdater, never()).markSent(any());
        verify(statusUpdater, never()).markFailed(any(), any());
    }

    // ---- multiple events: each gets its own mark* call -----------------

    @Test
    void dispatch_multipleMixedEvents_eachRoutedIndependently() {
        enableDispatcher();
        OperatorEvent ok = pendingEvent();
        OperatorEvent bad4xx = pendingEvent();
        OperatorEvent bad5xx = pendingEvent();

        when(operatorEventRepository.claimPendingBatch(anyInt(), any()))
                .thenReturn(List.of(ok, bad4xx, bad5xx));
        throwOnNotify(bad4xx, waeWithStatus(400));
        throwOnNotify(bad5xx, waeWithStatus(502));

        dispatcher.dispatch();

        verify(statusUpdater).markSent(ok.getId());
        verify(statusUpdater).markFailed(eq(bad4xx.getId()), any());
        verify(statusUpdater).markRetry(eq(bad5xx.getId()), any());
    }
}
