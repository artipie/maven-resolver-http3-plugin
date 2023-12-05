package com.artipie.aether.transport.http3;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.eclipse.aether.spi.connector.transport.GetTask;
import org.eclipse.aether.spi.connector.transport.PeekTask;
import org.eclipse.aether.spi.connector.transport.PutTask;
import org.eclipse.aether.spi.connector.transport.TransportListener;
import org.eclipse.aether.spi.connector.transport.Transporter;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class TransporterTest {

    private int port;

    private Server server;

    private CountDownLatch latch;

    @BeforeEach
    void init() throws Exception {
        this.server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);
        latch = new CountDownLatch(1);
        this.server.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception {
                response.setStatus(200);
                if ("GET".equals(request.getMethod())) {
                    response.write(true, ByteBuffer.wrap(getCommonsJar()), Callback.NOOP);
                } else {
                    response.write(true, ByteBuffer.allocate(0), Callback.NOOP);
                }
                Content.Chunk chunk = request.read();
                if (chunk.hasRemaining()) {
                    MatcherAssert.assertThat(
                        chunk.getByteBuffer().array(), new IsEqual<>(getCommonsJar())
                    );
                }
                latch.countDown();
                return false;
            }
        });
        this.server.start();
        this.port = connector.getLocalPort();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://repo.maven.apache.org/maven2/",
        "https://oss.sonatype.org/content/repositories/releases/"
    })
    void performsRequestToCentral(final String url) throws Exception {
        final byte[] data = this.getResource(url);
        MatcherAssert.assertThat(data, new IsEqual<>(this.getCommonsJar()));
    }

    @Test
    void getsResourceFromLocalhostViaHttp1() throws Exception {
        final byte[] data = this.getResource(String.format("http://localhost:%d", this.port));
        MatcherAssert.assertThat(data, new IsEqual<>(this.getCommonsJar()));
    }

    @Test
    void performsHeadRequest() throws Exception {
        final PeekTask task = new PeekTask(URI.create(MavenResolverIT.REMOTE_PATH));
        try (final Transporter transporter = new HttpTransporterFactory().newInstance(
            MavenResolverIT.newSession(),
            MavenResolverIT.newRepo(String.format("http://localhost:%d", this.port))
        )) {
            transporter.peek(task);
        }
        MatcherAssert.assertThat("Head performed", latch.await(1, TimeUnit.MINUTES));
    }

    @Test
    void performsPutRequest() throws Exception {
        final PutTask task = new PutTask(URI.create(MavenResolverIT.REMOTE_PATH))
            .setListener(new TransportListener() {});
        try (final Transporter transporter = new HttpTransporterFactory().newInstance(
            MavenResolverIT.newSession(),
            MavenResolverIT.newRepo(String.format("http://localhost:%d", this.port))
        )) {
            transporter.put(task);
        }
        MatcherAssert.assertThat("Put performed", latch.await(1, TimeUnit.MINUTES));
    }

    @AfterEach
    void close() throws Exception {
        this.server.stop();
        this.server.destroy();
    }

    private byte[] getResource(final String repo) throws Exception {
        final GetTask task = new GetTask(URI.create(MavenResolverIT.REMOTE_PATH))
            .setListener(new TransportListener() {});
        try (final Transporter transporter = new HttpTransporterFactory()
            .newInstance(MavenResolverIT.newSession(), MavenResolverIT.newRepo(repo))) {
            transporter.get(task);
        }
        return task.getDataBytes();
    }

    byte[] getCommonsJar() throws IOException {
        return getClass().getClassLoader().getResourceAsStream(MavenResolverIT.LOCAL_PATH).readAllBytes();
    }

}
