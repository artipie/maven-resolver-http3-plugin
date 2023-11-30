package com.artipie.aether.transport.http3;

import java.io.IOException;
import java.util.function.Consumer;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.utility.MountableFile;

public class ArtipieHTTP3IT {

    private static final Logger LOGGER = LoggerFactory.getLogger(ArtipieHTTP3IT.class);

    private GenericContainer<?> mavenClient;

    private GenericContainer<?> artipie;

    private final Consumer<OutputFrame> artipieLog =
        new Slf4jLogConsumer(LoggerFactory.getLogger(this.getClass())).withPrefix("ARTIPIE");

    /**
     * Container network.
     */
    private Network net;

    @BeforeEach
    void init() throws IOException, InterruptedException {
        this.net = Network.newNetwork();
        this.artipie =  new GenericContainer<>("artipie/artipie-ubuntu:latest")
            .withNetwork(this.net)
            .withNetworkAliases("artipie")
            .withLogConsumer(this.artipieLog)
            .withClasspathResourceMapping(
                "artipie.yaml", "/etc/artipie/artipie.yml", BindMode.READ_ONLY
            )
            .withClasspathResourceMapping(
                "my-maven-proxy.yaml", "/var/artipie/repo/my-maven-proxy.yaml", BindMode.READ_ONLY
            )
            .withWorkingDirectory("/w");
        this.mavenClient = new GenericContainer<>("http3-resolver:1.0-SNAPSHOT")
            .withNetwork(this.net)
            .withCommand("tail", "-f", "/dev/null")
            .withWorkingDirectory("/w");
        this.mavenClient.start();
        this.artipie.start();
        this.mavenClient.execInContainer("sleep", "5");
    }

    @Test
    void resolvesDependencies() throws IOException, InterruptedException {
        this.putClasspathResourceToClient("com/example/maven-http3/maven-settings.xml", "/w/settings.xml");
        this.putClasspathResourceToClient("com/example/maven-http3/pom.xml", "/w/pom.xml");
        final Container.ExecResult exec = this.mavenClient.execInContainer(
            "mvn", "install", "-s", "settings.xml", "-Daether.connector.https.securityMode=insecure"
        );
        String res = String.join("\n", exec.getStdout(), exec.getStderr());
        LOGGER.info(res);
        MatcherAssert.assertThat("Maven install is not successful", exec.getExitCode() == 0);
        MatcherAssert.assertThat(
            res,
            Matchers.stringContainsInOrder(
                "BUILD SUCCESS",
                "https://artipie:8091/my-maven-proxy/args4j/args4j/2.33/args4j-2.33.jar",
                "https://artipie:8091/my-maven-proxy/org/springframework/spring-web/6.1.0/spring-web-6.1.0.jar"
            )
        );
    }

    @AfterEach
    void close() {
        this.artipie.stop();
        this.mavenClient.stop();
        this.net.close();
    }

    private void putClasspathResourceToClient(final String res, final String path) {
        final MountableFile file = MountableFile.forClasspathResource(res);
        this.mavenClient.copyFileToContainer(file, path);
    }

}
