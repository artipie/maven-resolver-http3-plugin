package com.artipie.aether.transport.http3;

import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.internal.test.util.TestLocalRepositoryManager;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.spi.connector.transport.GetTask;
import org.eclipse.aether.spi.connector.transport.TransportListener;
import org.eclipse.aether.spi.connector.transport.Transporter;
import org.eclipse.aether.transfer.TransferCancelledException;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.http3.client.HTTP3Client;
import org.eclipse.jetty.http3.client.transport.HttpClientTransportOverHTTP3;
import org.junit.Ignore;
import org.junit.Test;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class HttpTransporterTest {

    @Test
    public void testConnection_http3check() throws Exception {
        final HTTP3Client h3Client = new HTTP3Client();
        final HttpClientTransportOverHTTP3 transport = new HttpClientTransportOverHTTP3(h3Client);
        final HttpClient client = new HttpClient(transport);
        client.start();
        final ContentResponse response = client.GET("https://http3check.net");
        assertEquals(200, response.getStatus());
        client.stop();
    }

    @Test
    @Ignore("https://github.com/eclipse/jetty.project/issues/10390")
    public void testConnection_nghttp2() throws Exception {
        final HTTP3Client h3Client = new HTTP3Client();
        final HttpClientTransportOverHTTP3 transport = new HttpClientTransportOverHTTP3(h3Client);
        final HttpClient client = new HttpClient(transport);
        client.start();
        final ContentResponse response = client.GET("https://nghttp2.org:4433"); //https://http3check.net
        assertEquals(200, response.getStatus());
        client.stop();
    }

    @Test
    public void testConnection_localhost() throws Exception {
        final HTTP3Client h3Client = new HTTP3Client();
        //h3Client.getQuicConfiguration().setSessionRecvWindow(64 * 1024 * 1024);
        HttpClientTransportOverHTTP3 transport = new HttpClientTransportOverHTTP3(h3Client);
        final HttpClient client = new HttpClient(transport);
        client.start();
        h3Client.getClientConnector().getSslContextFactory().setTrustAll(true);
        //9443 - nghttpx; 7443 - caddy
        final ContentResponse response = client.GET("https://localhost:7443/");
        System.out.println(new String(response.getContent(), StandardCharsets.UTF_8));
        assertEquals(200, response.getStatus());
        client.stop();
    }

    @Test
    public void testConnection_localhostPom() throws Exception {
        final HTTP3Client h3Client = new HTTP3Client();
        //h3Client.getQuicConfiguration().setSessionRecvWindow(64 * 1024 * 1024);
        HttpClientTransportOverHTTP3 transport = new HttpClientTransportOverHTTP3(h3Client);
        final HttpClient client = new HttpClient(transport);
        client.start();
        h3Client.getClientConnector().getSslContextFactory().setTrustAll(true);
        //9443 - nghttpx; 7443 - caddy
        final ContentResponse response = client.GET("https://localhost:7443/maven2/commons-cli/commons-cli/1.4/commons-cli-1.4.pom");
        System.out.println(new String(response.getContent(), StandardCharsets.UTF_8));
        assertEquals(200, response.getStatus());
        client.stop();
        client.destroy();
    }

    @Test
    public void testTransporter() throws Exception {
        final RepositorySystemSession session = newSession();
        final RemoteRepository repository = newRepo("https://localhost:7443/maven2");
        final HttpTransporterFactory factory = new HttpTransporterFactory();
        TransportListener listener = new TransportListener() {
            @Override
            public void transportStarted(long dataOffset, long dataLength) throws TransferCancelledException {
                super.transportStarted(dataOffset, dataLength);
            }
            @Override
            public void transportProgressed(ByteBuffer data) throws TransferCancelledException {
                super.transportProgressed(data);
            }
        };
        final GetTask task = new GetTask(URI.create("commons-cli/commons-cli/1.4/commons-cli-1.4.pom")).setListener(listener);
        try (final Transporter transporter = factory.newInstance(session, repository)) {
            transporter.get(task);
        }
        assertNotEquals(null, task.getDataBytes());
        System.err.println(new String(task.getDataBytes(), StandardCharsets.UTF_8));
        assertTrue(task.getDataBytes().length > 0);
    }

    private static DefaultRepositorySystemSession newSession() {
        DefaultRepositorySystemSession session = new DefaultRepositorySystemSession();
        session.setLocalRepositoryManager(new TestLocalRepositoryManager());
        return session;
    }

    private RemoteRepository newRepo(final String url) {
        return new RemoteRepository.Builder("test", "default", url).build();
    }
}
