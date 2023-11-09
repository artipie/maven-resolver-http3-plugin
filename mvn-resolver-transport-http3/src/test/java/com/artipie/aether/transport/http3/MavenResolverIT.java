package com.artipie.aether.transport.http3;

import org.eclipse.aether.ConfigurationProperties;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.internal.test.util.TestLocalRepositoryManager;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.spi.connector.transport.GetTask;
import org.eclipse.aether.spi.connector.transport.PutTask;
import org.eclipse.aether.spi.connector.transport.TransportListener;
import org.eclipse.aether.spi.connector.transport.Transporter;
import org.eclipse.jetty.client.*;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http3.client.HTTP3Client;
import org.eclipse.jetty.http3.client.transport.HttpClientTransportOverHTTP3;
import org.eclipse.jetty.io.Content;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.FixedHostPortGenericContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.InternetProtocol;
import org.testcontainers.containers.wait.strategy.ShellStrategy;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;
import static org.junit.Assert.assertEquals;

/**
 * Testing transport via containerized Caddy http3 server in proxy mode.
 */
public class MavenResolverIT {
    private static final String REMOTE_PATH = "commons-cli/commons-cli/1.4/commons-cli-1.4.jar";
    private static final String LOCAL_PATH = "commons-cli-1.4.jar";

    private static GenericContainer<?> caddyProxy;
    private static File tempFile;

    @Test
    public void testTransporterAuth() throws Exception {
        final byte[] data = testTransporter("https://demo:demo@localhost:7444/maven2");
        assertNotEquals(null, data);
        final byte[] local = getClass().getClassLoader().getResourceAsStream(LOCAL_PATH).readAllBytes();
        assertArrayEquals(local, data);
    }

    @Test
    public void testTransporterAuthFail() {
        HttpResponseException exception = assertThrows(
            "Invalid exception thrown", HttpResponseException.class,
            () -> testTransporter("https://demo1:demo1@localhost:7444/maven2")
        );
        assertEquals("401", exception.getMessage());
    }

    @Test
    public void testTransporterAnonAuthFail() {
        HttpResponseException exception = assertThrows(
            "Invalid exception thrown", HttpResponseException.class,
            () -> testTransporter("https://localhost:7444/maven2")
        );
        assertEquals("401", exception.getMessage());
    }

    @Test
    public void testTransporterAnon() throws Exception {
        final byte[] data = testTransporter("https://localhost:7443/maven2");
        assertNotEquals(null, data);
        final byte[] local = getClass().getClassLoader().getResourceAsStream(LOCAL_PATH).readAllBytes();
        assertArrayEquals(local, data);
    }

    @Test
    public void testAnonTransporterSuccess() throws Exception {
        final byte[] data = testTransporter("https://demo:demo@localhost:7443/maven2");
        assertNotNull(data);
        final byte[] local = getClass().getClassLoader().getResourceAsStream(LOCAL_PATH).readAllBytes();
        assertArrayEquals(local, data);
    }

    @Test()
    public void testTransporterInvalidUrl() {
        HttpRequestException exception = assertThrows(
            "Invalid exception thrown", HttpRequestException.class,
            () -> testTransporter("https://localhost:7440/maven2")
        );
        assertEquals("java.net.SocketTimeoutException: connect timeout", exception.getMessage());
    }

    private byte[] testTransporter(final String repo) throws Exception {
        final RepositorySystemSession session = newSession();
        final RemoteRepository repository = newRepo(repo);
        final HttpTransporterFactory factory = new HttpTransporterFactory();
        final GetTask task = new GetTask(URI.create(REMOTE_PATH))
            .setListener(new TransportListener() {});
        try (final Transporter transporter = factory.newInstance(session, repository)) {
            transporter.get(task);
        }
        return task.getDataBytes();
    }

    @Test
    public void testJettyLocalhostConnection() throws Exception {
        final HTTP3Client h3Client = new HTTP3Client();
        HttpClientTransportOverHTTP3 transport = new HttpClientTransportOverHTTP3(h3Client);
        final HttpClient client = new HttpClient(transport);
        client.start();
        h3Client.getClientConnector().getSslContextFactory().setTrustAll(true);
        final ContentResponse response = client.GET("https://localhost:7443/");
        System.out.println(new String(response.getContent(), StandardCharsets.UTF_8));
        assertEquals(200, response.getStatus());
        client.stop();
    }

    @Test
    public void testJettyLocalhostPut() throws Exception {
        resetPutServer();
        final HTTP3Client h3Client = new HTTP3Client();
        HttpClientTransportOverHTTP3 transport = new HttpClientTransportOverHTTP3(h3Client);
        final HttpClient client = new HttpClient(transport);
        client.start();
        h3Client.getClientConnector().getSslContextFactory().setTrustAll(true);
        final byte[] srcData = getClass().getClassLoader().getResourceAsStream(LOCAL_PATH).readAllBytes();
        final ContentResponse response = client.newRequest("https://localhost:7445/test1").
            method(HttpMethod.PUT).body(
                new InputStreamRequestContent(new ByteArrayInputStream(srcData))
            ).headers(httpFields -> httpFields.add(HttpHeader.CONTENT_LENGTH, srcData.length)).send();
        assertEquals(200, response.getStatus());
        caddyProxy.copyFileFromContainer("/srv/test1", tempFile.getPath());
        final byte[] dstData = Files.readAllBytes(Path.of(tempFile.getPath()));
        assertArrayEquals(srcData, dstData);
        client.stop();
    }

    @Test
    public void testTransporterPutAnon() throws Exception {
        final String repo = "https://localhost:7445/";
        resetPutServer();
        final HttpTransporterFactory factory = new HttpTransporterFactory();
        final PutTask task = new PutTask(URI.create("test1")).setListener(new TransportListener() {});
        final byte[] srcData = getClass().getClassLoader().getResourceAsStream(LOCAL_PATH).readAllBytes();
        task.setDataBytes(srcData);
        try (final Transporter transporter = factory.newInstance(newSession(), newRepo(repo))) {
            transporter.put(task);
        }
        caddyProxy.copyFileFromContainer("/srv/test1", tempFile.getPath());
        final byte[] dstData = Files.readAllBytes(Path.of(tempFile.getPath()));
        assertArrayEquals(srcData, dstData);
    }

    @Test
    public void testTransporterPutAuth() throws Exception {
        final String repo = "https://demo:demo@localhost:7446/";
        resetPutServer();
        final HttpTransporterFactory factory = new HttpTransporterFactory();
        final PutTask task = new PutTask(URI.create("test1")).setListener(new TransportListener() {});
        final byte[] srcData = getClass().getClassLoader().getResourceAsStream(LOCAL_PATH).readAllBytes();
        task.setDataBytes(srcData);
        try (final Transporter transporter = factory.newInstance(newSession(), newRepo(repo))) {
            transporter.put(task);
        }
        caddyProxy.copyFileFromContainer("/srv/test1", tempFile.getPath());
        final byte[] dstData = Files.readAllBytes(Path.of(tempFile.getPath()));
        assertArrayEquals(srcData, dstData);
    }

    @BeforeClass
    @SuppressWarnings("deprecation")
    public static void prepare() throws IOException, InterruptedException {
        try {
            caddyProxy = new FixedHostPortGenericContainer<>("library/caddy:2.7.5")
                .withReuse(false)
                .withFileSystemBind("src/test/resources/Caddyfile.proxy", "/etc/caddy/Caddyfile")
                .withFileSystemBind("src/test/resources/stunnel.pem", "/etc/caddy/stunnel.pem")
                .withFileSystemBind("src/test/resources/SimpleHTTPPutServer.py", "/etc/caddy/SimpleHTTPPutServer.py")
                .waitingFor(new ShellStrategy().withCommand("nc -u -z localhost 7443"))
                .withFixedExposedPort(7443, 7443, InternetProtocol.UDP)
                .withFixedExposedPort(7444, 7444, InternetProtocol.UDP)
                .withFixedExposedPort(7445, 7445, InternetProtocol.UDP)
                .withFixedExposedPort(7446, 7446, InternetProtocol.UDP)
                .withFixedExposedPort(7447, 7447, InternetProtocol.UDP)
                .withAccessToHost(true);
            caddyProxy.start();
            caddyProxy.execInContainer("apk add -f python3".split(" "));
            tempFile = File.createTempFile( "test_file", null);
            tempFile.deleteOnExit();
        }
        catch (Exception ex) {
            System.err.println(caddyProxy.getLogs());
            throw ex;
        }
    }

    @AfterClass
    public static void finish() {
        caddyProxy.stop();
        tempFile.delete();
    }

    private static DefaultRepositorySystemSession newSession() {
        DefaultRepositorySystemSession session = new DefaultRepositorySystemSession();
        session.setLocalRepositoryManager(new TestLocalRepositoryManager());
        session.setConfigProperty(ConfigurationProperties.HTTPS_SECURITY_MODE, ConfigurationProperties.HTTPS_SECURITY_MODE_INSECURE);
        return session;
    }

    private RemoteRepository newRepo(final String url) {
        return new RemoteRepository.Builder("test", "default", url).build();
    }

    private static void resetPutServer() throws InterruptedException, IOException {
        Container.ExecResult result = caddyProxy.execInContainer("sh", "-c", "cd /srv;killall -9 python3;rm -rf /srv/*;nohup python3 /etc/caddy/SimpleHTTPPutServer.py 8081 &");
        boolean deleted = tempFile.delete();
        assertEquals(0, result.getExitCode());
        assertTrue(deleted);
    }
}
