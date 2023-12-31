/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.artipie.aether.transport.http3;

import com.artipie.aether.transport.http3.checksum.ChecksumExtractor;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import static java.util.Objects.requireNonNull;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.commons.lang3.exception.UncheckedException;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.aether.ConfigurationProperties;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.AuthenticationContext;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.spi.connector.transport.AbstractTransporter;
import org.eclipse.aether.spi.connector.transport.GetTask;
import org.eclipse.aether.spi.connector.transport.PeekTask;
import org.eclipse.aether.spi.connector.transport.PutTask;
import org.eclipse.aether.spi.connector.transport.TransportTask;
import org.eclipse.aether.transfer.NoTransporterException;
import org.eclipse.aether.util.ConfigUtils;
import org.eclipse.aether.util.FileUtils;
import org.eclipse.jetty.client.BasicAuthentication;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpRequestException;
import org.eclipse.jetty.client.HttpResponseException;
import org.eclipse.jetty.client.InputStreamRequestContent;
import org.eclipse.jetty.client.InputStreamResponseListener;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.http.HttpFieldPreEncoder;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.http3.client.HTTP3Client;
import org.eclipse.jetty.http3.client.transport.HttpClientTransportOverHTTP3;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A transporter for HTTP/HTTPS.
 */
final class HttpTransporter extends AbstractTransporter {

    static final Logger LOGGER = LoggerFactory.getLogger("http3.plugin");

    private final static Set<String> CENTRAL = Set.of(
        "repo.maven.apache.org",
        "oss.sonatype.org",
        "packages.atlassian.com"
    );

    private final Map<String, ChecksumExtractor> checksumExtractors;

    private final AuthenticationContext repoAuthContext;

    private final AuthenticationContext proxyAuthContext;

    private final URI baseUri;

    private HttpClient http3Client;
    private HttpClient httpClient = null;

    private final int connectTimeout;
    private final String httpsSecurityMode;

    private String[] authInfo = null;

    HttpTransporter(
            Map<String, ChecksumExtractor> checksumExtractors,
            RemoteRepository repository,
            RepositorySystemSession session
    ) throws Exception {

        forceLoadHttp3Support();

        if (!"http".equalsIgnoreCase(repository.getProtocol()) && !"https".equalsIgnoreCase(repository.getProtocol())) {
            throw new NoTransporterException(repository);
        }
        this.checksumExtractors = requireNonNull(checksumExtractors, "checksum extractors must not be null");
        try {
            LOGGER.debug("Custom HttpTransporter repo: {}", repository);
            this.baseUri = new URI(repository.getUrl() + "/").normalize().parseServerAuthority();
            if (baseUri.isOpaque()) {
                throw new URISyntaxException(repository.getUrl(), "URL must not be opaque");
            }
        } catch (URISyntaxException e) {
            throw new NoTransporterException(repository, e.getMessage(), e);
        }

        this.repoAuthContext = AuthenticationContext.forRepository(session, repository);
        this.proxyAuthContext = AuthenticationContext.forProxy(session, repository);

        if (this.baseUri.getUserInfo() != null) {
            this.authInfo = this.baseUri.getUserInfo().split(":");
        } else if (this.repoAuthContext != null && this.repoAuthContext.get(AuthenticationContext.USERNAME) != null) {
            final String password = this.repoAuthContext.get(AuthenticationContext.PASSWORD);
            this.authInfo = new String[] {
                this.repoAuthContext.get(AuthenticationContext.USERNAME),
                password == null? "": password
            };
        }

        httpsSecurityMode = ConfigUtils.getString(
            session,
            ConfigurationProperties.HTTPS_SECURITY_MODE_DEFAULT,
            ConfigurationProperties.HTTPS_SECURITY_MODE + "." + repository.getId(),
            ConfigurationProperties.HTTPS_SECURITY_MODE
        );
        this.connectTimeout = ConfigUtils.getInteger(
            session,
            ConfigurationProperties.DEFAULT_CONNECT_TIMEOUT,
            ConfigurationProperties.CONNECT_TIMEOUT + "." + repository.getId(),
            ConfigurationProperties.CONNECT_TIMEOUT
        );
        this.chooseClient();
    }

    private HttpClient initOrGetHttpClient() {
        if (this.httpClient == null) {
            this.httpClient = new HttpClient();
            this.httpClient.setFollowRedirects(true);
            this.httpClient.setConnectTimeout(connectTimeout);
            SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
            sslContextFactory.setTrustAll(httpsSecurityMode.equals(ConfigurationProperties.HTTPS_SECURITY_MODE_INSECURE));
            httpClient.setSslContextFactory(sslContextFactory);
            try {
                this.httpClient.start();
            } catch (Exception e) {
                throw new UncheckedException(e);
            }
        }
        return this.httpClient;
    }

    private HttpClient initOrGetHttp3Client() {
        if (this.http3Client == null) {
            HTTP3Client h3Client = new HTTP3Client();
            HttpClientTransportOverHTTP3 transport = new HttpClientTransportOverHTTP3(h3Client);
            this.http3Client = new HttpClient(transport);
            this.http3Client.setFollowRedirects(true);
            this.http3Client.setConnectTimeout(connectTimeout);
            try {
                this.http3Client.start();
                h3Client.getClientConnector().getSslContextFactory().setTrustAll(
                    httpsSecurityMode.equals(ConfigurationProperties.HTTPS_SECURITY_MODE_INSECURE)
                );
            } catch (Exception e) {
                throw new UncheckedException(e);
            }
        }
        return this.http3Client;
    }

    @Override
    public int classify(Throwable error) {
        if (error instanceof HttpResponseException) {
            return ERROR_NOT_FOUND;
        }
        return ERROR_OTHER;
    }

    @Override
    protected void implPeek(PeekTask task) throws Exception {
        this.makeRequest(HttpMethod.HEAD, task, null, this.chooseClient());
    }

    @Override
    protected void implGet(GetTask task) throws Exception {
        final Pair<InputStream, HttpFields> response =
            this.makeRequest(HttpMethod.GET, task, null, this.chooseClient());
        final boolean resume = false;
        final File dataFile = task.getDataFile();
        long length = Long.parseLong(
            Optional.ofNullable(response.getValue().get(HttpHeader.CONTENT_LENGTH)).orElse("0")
        );
        if (dataFile == null) {
            try (final InputStream is = response.getKey()) {
                utilGet(task, is, true, length, resume);
                extractChecksums(response.getValue(), task);
            }
        } else {
            try (FileUtils.CollocatedTempFile tempFile = FileUtils.newTempFile(dataFile.toPath())) {
                task.setDataFile(tempFile.getPath().toFile());
                try (final InputStream is = response.getKey()) {
                    utilGet(task, is, true, length, resume);
                }
                tempFile.move();
            } finally {
                task.setDataFile(dataFile);
            }
        }
        if (task.getDataFile() != null) {
            final String lastModifiedHeader = response.getValue().get(HttpHeader.LAST_MODIFIED);
            if (lastModifiedHeader != null) {
                final DateFormat lastModifiedFormat = new SimpleDateFormat(
                    "EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US
                );
                final Date lastModified = lastModifiedFormat.parse(lastModifiedHeader);
                Files.setLastModifiedTime(
                    task.getDataFile().toPath(), FileTime.fromMillis(lastModified.getTime())
                );
            }
        }
    }

    @Override
    protected void implPut(PutTask task) throws Exception {
        try (final InputStream stream = task.newInputStream()) {
            this.makeRequest(HttpMethod.PUT, task,
                new InputStreamRequestContent(stream), this.chooseClient());
        }
    }

    @Override
    protected void implClose() {
        try {
            if (this.http3Client != null) {
                http3Client.stop();
                http3Client.destroy();
            }
            if (this.httpClient != null) {
                this.httpClient.stop();
                this.httpClient.destroy();
            }
        } catch (Exception e) {
            throw new UncheckedIOException(new IOException(e));
        }
        AuthenticationContext.close(repoAuthContext);
        AuthenticationContext.close(proxyAuthContext);
    }

    private Pair<InputStream, HttpFields> makeRequest(
        HttpMethod method, TransportTask task, Request.Content bodyContent, HttpClient client
    ) {
        final String url = this.baseUri.resolve(task.getLocation()).toString();
        if (this.authInfo != null) {
            client.getAuthenticationStore().addAuthenticationResult(
                new BasicAuthentication.BasicResult(this.baseUri, this.authInfo[0], this.authInfo[1])
            );
        }
        Request request = null;
        final HttpVersion version = this.httpVersion(client);
        try {
            InputStreamResponseListener listener = new InputStreamResponseListener();
            request = client.newRequest(url);
            request.method(method).headers(
                httpFields -> {
                if (bodyContent != null) {
                    httpFields.add(HttpHeader.CONTENT_TYPE, bodyContent.getContentType());
                    if (task instanceof PutTask) {
                        final long dataLength = ((PutTask)task).getDataLength();
                        if (dataLength > 0) {
                            httpFields.add(HttpHeader.CONTENT_LENGTH, dataLength);
                        }
                    }
                }
            }).body(bodyContent).send(listener);
            final Response response = listener.get(this.connectTimeout, TimeUnit.MILLISECONDS);
            if (response.getStatus() >= 300) {
                LOGGER.debug(
                    "{} request error status {}, method={}, url={}",
                    version, response.getStatus(), method, url
                );
                throw new HttpResponseException(Integer.toString(response.getStatus()), response);
            }
            LOGGER.debug(
                "{} request done, method={}, resp status={}, url={}", version, method, response.getStatus(), url
            );
            return new ImmutablePair<>(listener.getInputStream(), response.getHeaders());
        } catch (Exception ex) {
            LOGGER.debug(
                "{} request error={}: {}, method={}, url={}", version,
                ex.getClass(), ex.getMessage(), method, url
            );
            if (version == HttpVersion.HTTP_3 && ex instanceof TimeoutException) {
                LOGGER.debug("Repeat via HTTP/1.1 method={}, url={}", method, url);
                return this.makeRequest(method, task, bodyContent, this.initOrGetHttpClient());
            }
            throw new HttpRequestException(ex.getMessage(), request);
        }
    }

    private void extractChecksums(HttpFields response, GetTask task) {
        for (Map.Entry<String, ChecksumExtractor> extractorEntry : checksumExtractors.entrySet()) {
            Map<String, String> checksums = extractorEntry.getValue().extractChecksums(response);
            if (checksums != null) {
                checksums.forEach(task::setChecksum);
                return;
            }
        }
    }

    /**
     * Choose http client to initialize and perform request with: if host is present in known
     * central's hosts {@link HttpTransporter#CENTRAL}, http 1.1 client is used, otherwise we use http3 client.
     */
    private HttpClient chooseClient() {
        final HttpClient res;
        if (CENTRAL.contains(this.baseUri.getHost())) {
            res = Optional.ofNullable(this.httpClient).orElseGet(this::initOrGetHttpClient);
        } else {
            res = Optional.ofNullable(this.http3Client).orElseGet(this::initOrGetHttp3Client);
        }
        return res;
    }

    private HttpVersion httpVersion(final HttpClient client) {
        return client.getTransport() instanceof HttpClientTransportOverHTTP3 ? HttpVersion.HTTP_3 : HttpVersion.HTTP_1_1;
    }

    /**
     * TOOD: For unknown reason when running inside Maven, HttpFieldPreEncoder for HTTP3 is missing.
     * It is not available in Jetty static initializer when that library is loaded by Maven.
     * However, it is available the moment later.
     * Here I use reflection to force-register missing HttpFieldPreEncoder for HTTP3.
     * @throws NoSuchFieldException
     * @throws IllegalAccessException
     */
    private void forceLoadHttp3Support() throws NoSuchFieldException, IllegalAccessException {
        LOGGER.debug("Custom HttpTransporter.forceLoadHttp3Support() called!");
        // Checking http3 support is available (loaded)
        PreEncodedHttpField f = new PreEncodedHttpField("Host", "localhost");
        for (final HttpVersion v: HttpVersion.values()) {
            int len = 0;
            try {
                len = f.getEncodedLength(v);
            } catch (Exception ex) {
                len = -1;
            }
            //System.err.println("\tCustom HttpTransporter PreEncodedHttpField v=" + v + "; len=" + len);
        }

        // TODO: Force http3 initialization (HACK!)
        final ServiceLoader<HttpFieldPreEncoder> load = ServiceLoader.load(HttpFieldPreEncoder.class,PreEncodedHttpField.class.getClassLoader());
        HashMap<HttpVersion, HttpFieldPreEncoder> encoders = new HashMap<>();
        for (HttpFieldPreEncoder val: load) {
            LOGGER.debug("Custom HttpTransporter HttpFieldPreEncoder val={}", val);
            encoders.put(val.getHttpVersion(), val);
        }

        Field ff = PreEncodedHttpField.class.getDeclaredField("__encoders");
        ff.setAccessible(true);
        @SuppressWarnings("unchecked") EnumMap<HttpVersion, HttpFieldPreEncoder> obj = (EnumMap<HttpVersion, HttpFieldPreEncoder>)ff.get(null);
        if (encoders.containsKey(HttpVersion.HTTP_3) && !obj.containsKey(HttpVersion.HTTP_3)) {
            LOGGER.debug("Custom HttpTransporter adding to __encoders: {}, this = {}", obj, this);
            obj.put(HttpVersion.HTTP_3, encoders.get(HttpVersion.HTTP_3));
        }
        LOGGER.debug("Custom HttpTransporter __encoders AFTER: {}; this={}", obj, this);

        // Rechecking http3 support is available (loaded)
        f = new PreEncodedHttpField("Host", "localhost");
        for (final HttpVersion v: HttpVersion.values()) {
            int len = 0;
            try {
                len = f.getEncodedLength(v);
            } catch (Exception ex) {
                len = -1;
            }
            LOGGER.debug("Custom HttpTransporter PreEncodedHttpField v={}; len={}", v, len);
        }
    }
}
