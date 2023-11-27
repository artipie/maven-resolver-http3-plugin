package com.artipie.aether.transport.http3;

import java.io.IOException;
import java.nio.file.Path;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.MountableFile;

public class ArtipieHTTP3IT {

    /**
     * Container.
     */
    private GenericContainer<?> mavenClient;

    @TempDir
    Path tmp;

    @BeforeEach
    void init() {
        this.mavenClient = new GenericContainer<>("maven:3.6.3-jdk-11")
            .withCommand("tail", "-f", "/dev/null")
            .withWorkingDirectory("/w");
        this.mavenClient.start();
    }

    @Test
    void resolvesDependencies() throws IOException, InterruptedException {
        this.putClasspathResourceToClient("maven-http3/maven-settings.xml", "/w/settings.xml");
        this.putClasspathResourceToClient("maven-http3/pom.xml", "/w/pom.xml");
        final Container.ExecResult exec =
            this.mavenClient.execInContainer("mvn", "-s", "settings.xml", "dependency:resolve");
        System.out.println(exec.getStdout() + exec.getStderr());
        MatcherAssert.assertThat(
            "Resolved", exec.getExitCode() == 0
        );
    }


    private void putClasspathResourceToClient(final String res, final String path) {
        final MountableFile file = MountableFile.forClasspathResource(res);
        this.mavenClient.copyFileToContainer(file, path);
    }

}
