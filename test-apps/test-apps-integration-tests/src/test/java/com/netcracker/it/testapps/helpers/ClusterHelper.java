package com.netcracker.it.testapps.helpers;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.LocalPortForward;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class ClusterHelper {

    private static final KubernetesClient CLUSTER = new KubernetesClientBuilder().build();
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .readTimeout(Duration.ofSeconds(60))
            .writeTimeout(Duration.ofSeconds(60))
            .build();

    private static String namespace;

    private ClusterHelper() {
    }

    public static KubernetesClient cluster() {
        return CLUSTER;
    }

    public static String namespace() {
        if (namespace == null) {
            namespace = config("clouds.cloud.namespaces.namespace");
        }
        return namespace;
    }

    public static String config(String name) {
        String value = config(name, null);
        if (value == null) {
            throw new IllegalStateException("Missing required property or environment variable: " + name);
        }
        return value;
    }

    public static String config(String name, String fallback) {
        for (String candidate : List.of(name, name.replace('.', '_').toUpperCase(), "ENV_" + name)) {
            String value = System.getProperty(candidate);
            if (value == null || value.isBlank()) {
                value = System.getenv(candidate);
            }
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return fallback;
    }

    public static LocalPortForward toPod(String pod, int port) {
        return CLUSTER.pods().inNamespace(namespace()).withName(pod).portForward(port);
    }

    public static LocalPortForward toService(String service, int port) {
        return CLUSTER.services().inNamespace(namespace()).withName(service).portForward(port);
    }

    public static String url(LocalPortForward forward, String path) {
        return "http://127.0.0.1:" + forward.getLocalPort() + path;
    }

    /** A port-forward refuses connections for a moment after it is opened. */
    public static void awaitReachable(String url, String what) {
        awaitAnsweringAt(url, what, status -> true);
    }

    public static void awaitHealthy(String url, String what) {
        awaitAnsweringAt(url, what, status -> status == 200);
    }

    public static int status(String url) throws IOException {
        return status(new Request.Builder().url(url).get().build());
    }

    public static int status(Request request) throws IOException {
        try (Response response = HTTP.newCall(request).execute()) {
            return response.code();
        }
    }

    public static String execute(Request request, int expectedStatus) throws IOException {
        try (Response response = HTTP.newCall(request).execute()) {
            ResponseBody payload = response.body();
            String body = payload == null ? "" : payload.string();
            assertEquals(expectedStatus, response.code(), "unexpected response from " + request.url() + ": " + body);
            return body;
        }
    }

    /** The first request to a sample service waits for its datasource, so its failure is not a finding. */
    public static String executeEventually(Request request, int expectedStatus) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
        Throwable lastError = null;
        while (System.nanoTime() < deadline) {
            try {
                return execute(request, expectedStatus);
            } catch (IOException | AssertionError e) {
                lastError = e;
                sleep(Duration.ofSeconds(2));
            }
        }
        throw new AssertionError("No " + expectedStatus + " from " + request.url() + " in time", lastError);
    }

    public static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting", e);
        }
    }

    private static void awaitAnsweringAt(String url, String what, IntPredicate answer) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        IOException lastError = null;
        while (System.nanoTime() < deadline) {
            try {
                if (answer.test(status(url))) {
                    return;
                }
            } catch (IOException e) {
                lastError = e;
            }
            sleep(Duration.ofSeconds(1));
        }
        throw new AssertionError(what + " never answered at " + url, lastError);
    }
}
