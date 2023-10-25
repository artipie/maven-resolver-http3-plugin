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

import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.spi.connector.transport.Transporter;
import org.eclipse.aether.transfer.NoTransporterException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

/**
 * Helps to deal with URIs.
 */
final class UriUtils {

    public static void main(final String[] args) throws NoTransporterException {
        DefaultRepositorySystemSession session = new DefaultRepositorySystemSession();
        //session.setLocalRepositoryManager(new TestLocalRepositoryManager());
        final RemoteRepository repository = new RemoteRepository.Builder("test", "default", "https://localhost:7443/maven2/").build();
        final HttpTransporterFactory factory = new HttpTransporterFactory();
        final Transporter transporter = factory.newInstance(session, repository);
        transporter.close();
    }

    public static List<URI> getDirectories(URI base, URI uri) {
        List<URI> dirs = new ArrayList<>();
        for (URI dir = uri.resolve("."); !isBase(base, dir); dir = dir.resolve("..")) {
            dirs.add(dir);
        }
        return dirs;
    }

    private static boolean isBase(URI base, URI uri) {
        String path = uri.getRawPath();
        if (path == null || "/".equals(path)) {
            return true;
        }
        if (base != null) {
            URI rel = base.relativize(uri);
            if (rel.getRawPath() == null || rel.getRawPath().isEmpty() || rel.equals(uri)) {
                return true;
            }
        }
        return false;
    }
}
