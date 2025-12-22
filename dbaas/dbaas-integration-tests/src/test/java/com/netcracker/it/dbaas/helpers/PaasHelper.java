package com.netcracker.it.dbaas.helpers;

import com.netcracker.it.dbaas.test.AbstractIT;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarSource;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.Failsafe;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static com.netcracker.it.dbaas.test.AbstractIT.DBAAS_SERVICE_NAME;
import static com.netcracker.it.dbaas.test.AbstractIT.DEFAULT_RETRY_POLICY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
public class PaasHelper {

    private final static Duration SCALE_WAIT_TIME_MINUTES = Duration.ofMinutes(3);

    private final KubernetesClient kubernetesClient;

    public PaasHelper(KubernetesClient kubernetesClient) {
        this.kubernetesClient = kubernetesClient;
    }

    public int replicasNumber() {
        return getDeployment(kubernetesClient).getStatus().getReplicas();
    }

    public void toProdMode() {
        changeEnv("PRODUCTION_MODE", Boolean.toString(true));
    }

    public void toDevMode() {
        changeEnv("PRODUCTION_MODE", Boolean.toString(false));
    }

    private void changeEnv(String envName, String newEnvValue) {
        log.info("Start pod env updating, {}={}", envName, newEnvValue);
        Function<Container, Boolean> envUpdater = container -> {
            List<EnvVar> env = container.getEnv();
            String currentEnvValue = env.stream().filter(o -> envName.equals(o.getName()))
                    .map(EnvVar::getValue).findFirst().orElse("");
            if (currentEnvValue.equals(newEnvValue)) {
                return false;
            }
            env.stream().filter(o -> envName.equals(o.getName())).findFirst().ifPresentOrElse(
                    o -> o.setValue(newEnvValue),
                    () -> env.add(new EnvVar(envName, newEnvValue, new EnvVarSource())));
            container.setEnv(env);
            return true;
        };

        Boolean configUpdated;
        Long observedGeneration;
        int requiredPodsCount;
        Deployment deployment = getDeployment(kubernetesClient);
        log.info(deployment.getStatus().toString());
        observedGeneration = deployment.getStatus().getObservedGeneration();
        requiredPodsCount = deployment.getStatus().getReplicas();
        Container container = deployment.getSpec().getTemplate().getSpec().getContainers().getFirst();
        configUpdated = envUpdater.apply(container);
        kubernetesClient.apps().deployments().resource(deployment).createOrReplace();
        log.info(getDeployment(kubernetesClient).getStatus().toString());

        if (!configUpdated) {
            log.info("New pod is not required");
            return;
        }

        Failsafe.with(DEFAULT_RETRY_POLICY.copy().withMaxDuration(SCALE_WAIT_TIME_MINUTES)).run(() -> {
            DeploymentStatus status = getDeployment(kubernetesClient).getStatus();
            log.info(status.toString());
            assertTrue(observedGeneration < status.getObservedGeneration());
            assertEquals(requiredPodsCount, (int) Optional.ofNullable(status.getReplicas()).orElse(0));
            assertEquals(requiredPodsCount, (int) Optional.ofNullable(status.getReadyReplicas()).orElse(0));
            assertEquals(requiredPodsCount, (int) Optional.ofNullable(status.getUpdatedReplicas()).orElse(0));
            List<Pod> pods = kubernetesClient.pods()
                    .withLabel("name", DBAAS_SERVICE_NAME).list().getItems();
            log.info("Pods count: {}", pods.size());
            assertEquals(requiredPodsCount, pods.size());
        });

        log.info("Pod env successfully updated");
    }

    public void scaleDbaas(int replicas) {
        Deployment deployment = getDeployment(kubernetesClient);
        deployment.getSpec().setReplicas(replicas);
        kubernetesClient.apps().deployments().resource(deployment).createOrReplace();
        Failsafe.with(DEFAULT_RETRY_POLICY.copy().withMaxDuration(SCALE_WAIT_TIME_MINUTES)).run(() ->
                assertEquals(replicas, (int) Optional.ofNullable(getDeployment(kubernetesClient)
                        .getStatus()
                        .getAvailableReplicas()).orElse(0)));
    }

    private Deployment getDeployment(KubernetesClient kubernetesClient) {
        return kubernetesClient.apps().deployments().withName(AbstractIT.DBAAS_SERVICE_NAME).get();
    }
}
