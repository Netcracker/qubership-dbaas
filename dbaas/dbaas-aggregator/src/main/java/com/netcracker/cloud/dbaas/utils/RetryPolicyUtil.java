package com.netcracker.cloud.dbaas.utils;

import com.netcracker.cloud.dbaas.exceptions.AdapterException;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.RetryPolicy;

import java.time.Duration;

import static com.netcracker.cloud.dbaas.utils.RestClientExceptionUtil.is4xxError;

@Slf4j
public class RetryPolicyUtil {
    public static RetryPolicy<Object> buildRetryPolicy(int retryAttempts, Duration retryDelay) {
        return new RetryPolicy<>()
                .handle(AdapterException.class)
                .abortIf((_, e) -> e != null && is4xxError(e))
                .withMaxRetries(retryAttempts)
                .withDelay(retryDelay);
    }
}
