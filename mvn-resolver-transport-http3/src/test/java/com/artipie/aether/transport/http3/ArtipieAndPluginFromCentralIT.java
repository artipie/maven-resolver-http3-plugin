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
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.MountableFile;

/**
 * In this test we use this plugin from maven central to build test maven project and obtain
 * dependencies from artipie via HTTP/3 protocol.
 * Various maven repositories configuration settings are tested.
 */
public class ArtipieAndPluginFromCentralIT {

    private static final Logger LOGGER =
        LoggerFactory.getLogger(ArtipieAndPluginFromCentralIT.class);

    private GenericContainer<?> mavenClient;

    private GenericContainer<?> artipie;

    private final Consumer<OutputFrame> artipieLog =
        new Slf4jLogConsumer(LoggerFactory.getLogger("ARTIPIE"));

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
        this.mavenClient = new GenericContainer<>(
            new ImageFromDockerfile("maven-client").withFileFromClasspath(
                "Dockerfile", "ArtipieAndPluginFromCentralIT/Dockerfile"
            )
        )
            .withNetwork(this.net)
            .withCommand("tail", "-f", "/dev/null")
            .withWorkingDirectory("/w");
        this.mavenClient.start();
        this.artipie.start();
        this.mavenClient.execInContainer("sleep", "5");
    }

    /**
     * Here artipie HTTP/3 repository is specified in maven settings.xml file. Maven client uses this
     * repository to download dependencies only (not plugins). Thus, maven client does the following:<p/>
     * 1) downloads maven-resolver-transport-http3 and its deps from central directly<p/>
     * 2) once downloaded, maven-resolver-transport-http3 plugin is activated and
     *    project dependencies are downloaded from repository, specified in settings.xml file via HTTP/3<p/>
     * 3) maven client needs some other plugins to build the project, and it tries to obtain them
     *    from central still using maven-resolver-transport-http3, and plugin
     *    performs requests to central via HTTP/1.1<p/>
     */
    @Test
    void buildsWithMavenProfile() throws IOException, InterruptedException {
        this.putClasspathResourceToClient("ArtipieAndPluginFromCentralIT/com/example/test-maven-profile/maven-settings.xml", "/w/settings.xml");
        this.putClasspathResourceToClient("ArtipieAndPluginFromCentralIT/com/example/test-maven-profile/pom.xml", "/w/pom.xml");
        final Container.ExecResult exec = this.mavenClient.execInContainer(
            "mvn", "install", "-DskipTests", "-s", "settings.xml", "-Daether.connector.https.securityMode=insecure"
        );
        String res = String.join("\n", exec.getStdout(), exec.getStderr());
        LOGGER.info(res);
        MatcherAssert.assertThat("Maven install status is not successful", exec.getExitCode() == 0);
        MatcherAssert.assertThat(
            res,
            Matchers.stringContainsInOrder(
                "Downloaded from central: https://repo.maven.apache.org/maven2/com/artipie/maven/resolver/maven-resolver-transport-http3",
                "BUILD SUCCESS",
                "Request over HTTP/3.0 done, method=GET, resp status=200, url=https://artipie:8091/my-maven-proxy/args4j/args4j/2.33/args4j-2.33.jar",
                "Request over HTTP/3.0 done, method=GET, resp status=200, url=https://artipie:8091/my-maven-proxy/org/springframework/spring-web/6.1.0/spring-web-6.1.0.jar",
                "Request over HTTP/1.1 done, method=GET, resp status=200, url=https://repo.maven.apache.org/maven2/org/apache/maven/maven-archiver/3.6.0/maven-archiver-3.6.0.pom"
            )
        );
    }

    /**
     * Here repository is specified in pom.xml file, maven client work logic is the same as in
     * {@link ArtipieAndPluginFromCentralIT#buildsWithMavenProfile()}.
     */
    @Test
    void buildsWithPomRepo() throws IOException, InterruptedException {
        this.putClasspathResourceToClient("ArtipieAndPluginFromCentralIT/com/example/test-pom-repo/pom.xml", "/w/pom.xml");
        final Container.ExecResult exec = this.mavenClient.execInContainer(
            "mvn", "install", "-Daether.connector.https.securityMode=insecure"
        );
        String res = String.join("\n", exec.getStdout(), exec.getStderr());
        LOGGER.info(res);
        MatcherAssert.assertThat("Maven install status is not successful", exec.getExitCode() == 0);
        MatcherAssert.assertThat(
            res,
            Matchers.stringContainsInOrder(
                "Downloaded from central: https://repo.maven.apache.org/maven2/com/artipie/maven/resolver/maven-resolver-transport-http3",
                "BUILD SUCCESS",
                "Request over HTTP/3.0 done, method=GET, resp status=200, url=https://artipie:8091/my-maven-proxy/args4j/args4j/2.33/args4j-2.33.jar",
                "Request over HTTP/3.0 done, method=GET, resp status=200, url=https://artipie:8091/my-maven-proxy/org/springframework/spring-web/6.1.0/spring-web-6.1.0.jar",
                "Request over HTTP/1.1 done, method=GET, resp status=200, url=https://repo.maven.apache.org/maven2/org/apache/maven/maven-archiver/3.6.0/maven-archiver-3.6.0.pom"
            )
        );
    }

    /**
     * In this example project we specify both repositories for plugins and dependencies in pom.xml.
     * In the case, when maven client is not able to download plugin from custom repo, it switches
     * to central. Thus, maven client does the following:<p/>
     * 1) tries to get maven-resolver-transport-http3 plugin from artipie via HTTP/1.1, but fails
     *    and switches to central<p/>
     * 2) once downloaded, maven-resolver-transport-http3 plugin is activated and
     *   all other dependencies and plugins are downloaded from artipie via HTTP/3
     */
    @Test
    void buildsWithPomPluginRepo() throws IOException, InterruptedException {
        this.putClasspathResourceToClient("ArtipieAndPluginFromCentralIT/com/example/test-pom-plugin-repo/pom.xml", "/w/pom.xml");
        final Container.ExecResult exec = this.mavenClient.execInContainer(
            "mvn", "install", "-Daether.connector.https.securityMode=insecure"
        );
        String res = String.join("\n", exec.getStdout(), exec.getStderr());
        LOGGER.info(res);
        MatcherAssert.assertThat("Maven install status is not successful", exec.getExitCode() == 0);
        MatcherAssert.assertThat(
            res,
            Matchers.stringContainsInOrder(
                "Downloaded from central: https://repo.maven.apache.org/maven2/com/artipie/maven/resolver/maven-resolver-transport-http3",
                "BUILD SUCCESS",
                "Request over HTTP/3.0 done, method=GET, resp status=200, url=https://artipie:8091/my-maven-proxy/args4j/args4j/2.33/args4j-2.33.jar",
                "Request over HTTP/3.0 done, method=GET, resp status=200, url=https://artipie:8091/my-maven-proxy/org/springframework/spring-web/6.1.0/spring-web-6.1.0.jar",
                "Request over HTTP/3.0 done, method=GET, resp status=200, url=https://artipie:8091/my-maven-proxy/org/apache/maven/maven-archiver/3.6.0/maven-archiver-3.6.0.pom"
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
