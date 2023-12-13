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

/**
 * In this test this plugin is build locally and used in maven project to obtain dependencies over HTTP/3.
 * This test uses two docker containers:<p>
 * 1) {@link ArtipieAndLocalPluginHTTP3IT#artipie} is container with latest artipie version running on ubuntu 22.04 and java 21<p>
 * 2) {@link ArtipieAndLocalPluginHTTP3IT#mavenClient} is ubuntu 22.04 based container with this plugin built with maven 3.9.5 under java 21,
 * see ./Dockerfile<p>
 *
 * The containers are connected via docker {@link Network} with alias 'artipie'. Artipie container
 * runs HTTP3 maven-proxy repository available at <code>https://artipie:8091/my-maven-proxy/</code>.
 * <p>
 * Test maven project {@code ./resources/com/example/maven-http3} which uses this plugin is added to
 * {@link ArtipieAndLocalPluginHTTP3IT#mavenClient} and built with maven settings
 * {@code ./resources/com/example/maven-http3/maven-settings.xml}. The dependencies of this project
 * are obtained from Artipie via HTTP3.
 *
 */
public class ArtipieAndLocalPluginHTTP3IT {

    private static final Logger LOGGER = LoggerFactory.getLogger("ArtipieAndLocalPluginHTTP3IT");

    private GenericContainer<?> mavenClient;

    private GenericContainer<?> artipie;

    private final Consumer<OutputFrame> artipieLog =
        new Slf4jLogConsumer(LoggerFactory.getLogger(this.getClass())).withPrefix("ARTIPIE");

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
                "HTTP/3.0 request done, method=GET, resp status=200, url=https://artipie:8091/my-maven-proxy/args4j/args4j/2.33/args4j-2.33.jar",
                "HTTP/3.0 request done, method=GET, resp status=200, url=https://artipie:8091/my-maven-proxy/org/springframework/spring-web/6.1.0/spring-web-6.1.0.jar",
                "BUILD SUCCESS"
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
