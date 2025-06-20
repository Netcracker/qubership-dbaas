package org.qubership.cloud.dbaas.mock;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.TransactionRunnerOptions;

import org.mockito.MockedStatic;

import java.util.concurrent.Callable;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

public class MockedQuarkusTransactionRunnableWrapper implements Runnable {
    private Runnable runnable;

    private MockedQuarkusTransactionRunnableWrapper(Runnable runnable) {
        this.runnable = runnable;
    }

    public static Runnable withStaticMocks(Runnable runnable) {
        return new MockedQuarkusTransactionRunnableWrapper(runnable);
    }

    @Override
    public void run() {
        MockedStatic<QuarkusTransaction> mockedStatic = mockStatic(QuarkusTransaction.class);
        TransactionRunnerOptions txRunner = mock(TransactionRunnerOptions.class);
        doAnswer(invocationOnMock -> {
            ((Runnable) invocationOnMock.getArgument(0)).run();
            return null;
        }).when(txRunner).run(any());
        doAnswer(invocationOnMock -> ((Callable) invocationOnMock.getArgument(0)).call()).when(txRunner).call(any());
        mockedStatic.when(QuarkusTransaction::joiningExisting).thenReturn(txRunner);
        mockedStatic.when(QuarkusTransaction::requiringNew).thenReturn(txRunner);
        try {
            runnable.run();
        } finally {
            mockedStatic.close();
        }
    }
}
