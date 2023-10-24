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

import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.AuthenticationContext;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.spi.connector.transport.AbstractTransporter;
import org.eclipse.aether.spi.connector.transport.GetTask;
import org.eclipse.aether.spi.connector.transport.PeekTask;
import org.eclipse.aether.spi.connector.transport.PutTask;
import org.eclipse.aether.spi.connector.transport.TransportTask;
import org.eclipse.aether.transfer.NoTransporterException;
import org.eclipse.aether.transfer.TransferCancelledException;
import org.eclipse.aether.util.ConfigUtils;
import org.eclipse.aether.util.FileUtils;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpResponseException;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.http.HttpFieldPreEncoder;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.client.HTTP3Client;
import org.eclipse.jetty.http3.client.transport.HttpClientTransportOverHTTP3;
import org.eclipse.jetty.util.TypeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.Objects.requireNonNull;

/**
 * A transporter for HTTP/HTTPS.
 */
final class HttpTransporter extends AbstractTransporter {

    static final String BIND_ADDRESS = "aether.connector.bind.address";

    static final String SUPPORT_WEBDAV = "aether.connector.http.supportWebDav";

    static final String PREEMPTIVE_PUT_AUTH = "aether.connector.http.preemptivePutAuth";

    static final String USE_SYSTEM_PROPERTIES = "aether.connector.http.useSystemProperties";

    static final String HTTP_RETRY_HANDLER_NAME = "aether.connector.http.retryHandler.name";

    private static final String HTTP_RETRY_HANDLER_NAME_STANDARD = "standard";

    private static final String HTTP_RETRY_HANDLER_NAME_DEFAULT = "default";

    static final String HTTP_RETRY_HANDLER_REQUEST_SENT_ENABLED =
            "aether.connector.http.retryHandler.requestSentEnabled";

    private static final Pattern CONTENT_RANGE_PATTERN =
            Pattern.compile("\\s*bytes\\s+([0-9]+)\\s*-\\s*([0-9]+)\\s*/.*");

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpTransporter.class);

    private final Map<String, Object> checksumExtractors;

    private final AuthenticationContext repoAuthContext;

    private final AuthenticationContext proxyAuthContext;

    private final URI baseUri;

    private final HttpClient client;

/*    private final Map<?, ?> headers;

    private final boolean preemptiveAuth;

    private final boolean preemptivePutAuth;

    private final boolean supportWebDav;*/

    HttpTransporter(
            Map<String, Object> checksumExtractors,
            RemoteRepository repository,
            RepositorySystemSession session)
        throws Exception {
        System.err.println("Custom HttpTransporter created!!!");

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

        if (!"http".equalsIgnoreCase(repository.getProtocol()) && !"https".equalsIgnoreCase(repository.getProtocol())) {
            throw new NoTransporterException(repository);
        }
        this.checksumExtractors = requireNonNull(checksumExtractors, "checksum extractors must not be null");
        try {
            System.err.println("\tCustom HttpTransporter repo: " + repository.toString());
            this.baseUri = new URI(repository.getUrl()).parseServerAuthority();
            if (baseUri.isOpaque()) {
                throw new URISyntaxException(repository.getUrl(), "URL must not be opaque");
            }
        } catch (URISyntaxException e) {
            throw new NoTransporterException(repository, e.getMessage(), e);
        }

        this.repoAuthContext = AuthenticationContext.forRepository(session, repository);
        this.proxyAuthContext = AuthenticationContext.forProxy(session, repository);

        //TODO:
        // ConfigurationProperties: HTTPS_SECURITY_MODE; CONNECT_TIMEOUT, etc.

        HTTP3Client h3Client = new HTTP3Client();
        //h3Client.getQuicConfiguration().setSessionRecvWindow(64 * 1024 * 1024);
        HttpClientTransportOverHTTP3 transport = new HttpClientTransportOverHTTP3(h3Client);
        this.client = new HttpClient(transport);
        this.client.setFollowRedirects(true);
        this.client.start();
        h3Client.getClientConnector().getSslContextFactory().setTrustAll(true);
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
        // TODO: http HEAD
    }

    @Override
    protected void implGet(GetTask task) throws Exception {
        final String url = new URL(this.baseUri.toURL(), task.getLocation().toString()).toString();

        System.err.println("Custom HttpTransporter.implGet() called! " + url);
        //System.err.println("\tURI: " + new URI(task.getLocation().getScheme(), task.getLocation().getAuthority(), task.getLocation().getPath() + '/', null, null));
        ContentResponse response = this.client.GET(url);

        final boolean resume = false; // TODO: HttpHeader.CONTENT_RANGE
        final File dataFile = task.getDataFile();
        if (dataFile == null) {
            try (final InputStream is = new ByteArrayInputStream(response.getContent())) {
                utilGet(task, is, true, response.getContent().length, resume);
            }
        } else {
            try (FileUtils.CollocatedTempFile tempFile = FileUtils.newTempFile(dataFile.toPath())) {
                task.setDataFile(tempFile.getPath().toFile());
                try (final InputStream is = new ByteArrayInputStream(response.getContent())) {
                    utilGet(task, is, true, response.getContent().length, resume);
                }
                tempFile.move();
            } finally {
                task.setDataFile(dataFile);
            }
        }
        if (task.getDataFile() != null) {
            final String lastModifiedHeader =
                response.getHeaders().get(HttpHeader.LAST_MODIFIED);
            if (lastModifiedHeader != null) {
                Date lastModified = new Date(Date.parse(lastModifiedHeader));
                if (lastModified != null) {
                    Files.setLastModifiedTime(
                        task.getDataFile().toPath(), FileTime.fromMillis(lastModified.getTime())
                    );
                }
            }
        }
    }

    @Override
    protected void implPut(PutTask task) throws Exception {

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
}
