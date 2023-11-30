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
import java.util.concurrent.TimeUnit;
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

/**
 * A transporter for HTTP/HTTPS.
 */
final class HttpTransporter extends AbstractTransporter {

    private final Map<String, ChecksumExtractor> checksumExtractors;

    private final AuthenticationContext repoAuthContext;

    private final AuthenticationContext proxyAuthContext;

    private final URI baseUri;

    private final HttpClient client;
    private final int connectTimeout;

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
            System.err.println("\tCustom HttpTransporter repo: " + repository.toString());
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

        String httpsSecurityMode = ConfigUtils.getString(
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

        HTTP3Client h3Client = new HTTP3Client();
        HttpClientTransportOverHTTP3 transport = new HttpClientTransportOverHTTP3(h3Client);
        this.client = new HttpClient(transport);
        this.client.setFollowRedirects(true);
        this.client.setConnectTimeout(connectTimeout);
        this.client.start();
        h3Client.getClientConnector().getSslContextFactory().setTrustAll(
            httpsSecurityMode.equals(ConfigurationProperties.HTTPS_SECURITY_MODE_INSECURE)
        );
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
        this.makeRequest(HttpMethod.HEAD, task, null);
    }

    @Override
    protected void implGet(GetTask task) throws Exception {
        final Pair<InputStream, HttpFields> response = this.makeRequest(HttpMethod.GET, task, null);
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
                new InputStreamRequestContent(stream)
            );
        }
    }

    @Override
    protected void implClose() {
        try {
            client.stop();
            client.destroy();
        } catch (Exception e) {
            throw new UncheckedIOException(new IOException(e));
        }
        AuthenticationContext.close(repoAuthContext);
        AuthenticationContext.close(proxyAuthContext);
    }

    private Pair<InputStream, HttpFields> makeRequest(
        HttpMethod method, TransportTask task, Request.Content bodyContent
    ) {
        final String url = this.baseUri.resolve(task.getLocation()).toString();
        System.err.printf("Custom HttpTransporter.makeRequest() called! Method: %s; URL: %s%n", method.toString(), url);
        if (this.authInfo != null) {
            this.client.getAuthenticationStore().addAuthenticationResult(
                new BasicAuthentication.BasicResult(this.baseUri, this.authInfo[0], this.authInfo[1])
            );
        }
        try {
            InputStreamResponseListener listener = new InputStreamResponseListener();
            this.client.newRequest(url).method(method).headers(
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
                System.err.println("Response status not success " + response.getStatus());
                throw new HttpResponseException(Integer.toString(response.getStatus()), response);
            }
            return new ImmutablePair<>(listener.getInputStream(), response.getHeaders());
        } catch (Exception ex) {
            System.err.println("Error on request " + ex.getMessage());
            throw new HttpRequestException(ex.getMessage(), this.client.newRequest(url));
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
     * TOOD: For unknown reason when running inside Maven, HttpFieldPreEncoder for HTTP3 is missing.
     * It is not available in Jetty static initializer when that library is loaded by Maven.
     * However, it is available the moment later.
     * Here I use reflection to force-register missing HttpFieldPreEncoder for HTTP3.
     * @throws NoSuchFieldException
     * @throws IllegalAccessException
     */
    private void forceLoadHttp3Support() throws NoSuchFieldException, IllegalAccessException {
        System.err.println("Custom HttpTransporter.forceLoadHttp3Support() called!");
        // Checking http3 support is available (loaded)
        PreEncodedHttpField f = new PreEncodedHttpField("Host", "localhost");
        for (final HttpVersion v: HttpVersion.values()) {
            int len = 0;
            try {
                len = f.getEncodedLength(v);
            } catch (Exception ex) {
                len = -1;
            }
            System.err.println("\tCustom HttpTransporter PreEncodedHttpField v=" + v + "; len=" + len);
        }

        // TODO: Force http3 initialization (HACK!)
        final ServiceLoader<HttpFieldPreEncoder> load = ServiceLoader.load(HttpFieldPreEncoder.class,PreEncodedHttpField.class.getClassLoader());
        /*ServiceLoader<HttpFieldPreEncoder> load = null;
        ClassLoader saveCl = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(PreEncodedHttpField.class.getClassLoader());
            load = ServiceLoader.load(HttpFieldPreEncoder.class);
        }finally {
            Thread.currentThread().setContextClassLoader(saveCl);
        }*/

        /*System.err.println("\tCustom HttpTransporter ServiceLoader=" + load);
        Stream<ServiceLoader.Provider<HttpFieldPreEncoder>> providerStream = TypeUtil.serviceProviderStream(load);
        System.err.println("\tCustom HttpTransporter Stream<ServiceLoader.Provider<HttpFieldPreEncoder>> = " + providerStream);
        ArrayList<Integer> calls = new ArrayList<>();
        providerStream.forEach((provider) -> {
            try {
                calls.add(calls.size());
                HttpFieldPreEncoder encoder = (HttpFieldPreEncoder)provider.get();
                HttpVersion v = encoder.getHttpVersion();
                System.err.println("\tCustom HttpTransporter HttpFieldPreEncoder: encoder=" + encoder + "; ver=" + v);
            } catch (RuntimeException | Error var3) {
                System.err.println("\tCustom HttpTransporter Error processing encoder: " + provider.get());
            }
        });
        System.err.println("\tCustom HttpTransporter providerStream calls=" + calls.size());*/

        HashMap<HttpVersion, HttpFieldPreEncoder> encoders = new HashMap<>();
        for (HttpFieldPreEncoder val: load) {
            System.err.println("\tCustom HttpTransporter HttpFieldPreEncoder val=" + val);
            encoders.put(val.getHttpVersion(), val);
        }

        Field ff = PreEncodedHttpField.class.getDeclaredField("__encoders");
        ff.setAccessible(true);
        String fldDescr = ff.get(null).toString();
        System.err.println("\tCustom HttpTransporter __encoders BEFORE: " + fldDescr + "; this=" + this);
        @SuppressWarnings("unchecked") EnumMap<HttpVersion, HttpFieldPreEncoder> obj = (EnumMap<HttpVersion, HttpFieldPreEncoder>)ff.get(null);
        if (encoders.containsKey(HttpVersion.HTTP_3) && !obj.containsKey(HttpVersion.HTTP_3)) {
            System.err.println("\tCustom HttpTransporter adding to __encoders: " + obj + "; this=" + this);
            obj.put(HttpVersion.HTTP_3, encoders.get(HttpVersion.HTTP_3));
        }
        System.err.println("\tCustom HttpTransporter __encoders AFTER: " + obj + "; this=" + this);

        // Rechecking http3 support is available (loaded)
        f = new PreEncodedHttpField("Host", "localhost");
        for (final HttpVersion v: HttpVersion.values()) {
            int len = 0;
            try {
                len = f.getEncodedLength(v);
            } catch (Exception ex) {
                len = -1;
            }
            System.err.println("\tCustom HttpTransporter PreEncodedHttpField v=" + v + "; len=" + len);
        }
    }
}
