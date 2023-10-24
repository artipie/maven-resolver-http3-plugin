## Maven http3 experiments

### Maven transport APIs

`Maven Wagon` was main maven transport/API for a long time, but since Maven 3.9 release Wagon becoming obsolete
`Maven Artifact Resolver` default transport/API since Maven 3.9 release

### HTTP1.x basic plugins

These are distilled versions of standard maven http 1.x plugins with minimized dependencies,
minor code changes and debug `stderr` "logging". They are useful as reference starting point for 
new maven extensions and for maven testing/debugging.

- `testing/mvn-wagon-http-light` uses Maven Wagon transport.
- `testing/mvn-resolver-transport-http` uses Maven Artifact Resolver transport.

### HTTP3 new experimental plugin

- `mvn-resolver-transport-http3` uses Jetty HTTP3 client library and Maven Artifact Resolver API.

### HTTP3 demo/testing

For building and testing http3 tools, see `http3.md`

#### nghttp3 server 

`nghttpx` server and/or jetty http3 client have bugs, so at the moment nghttpx is unusable with jetty client. Issues:
* https://github.com/eclipse/jetty.project/issues/10390
* https://github.com/nghttp2/nghttp2/issues/1938

#### Caddy http3 server

For now `Caddy` http3 server is used for testing plugin.
- `testing/Caddyfile` main config file, provides settings for proxy mode + logging
- `testing/Caddyfile.http` test config file with simple response with http version used
- `testing/stunnel.pem` test certificate from `curl` for convinience

#### Testing plugin

```shell
cd testing
caddy run
curl -kv --http3 https://localhost.org:7433/
cd helloworld-src
rm -rf $HOME/.m2/repository/commons-cli/commons-cli/1.4
time mvn clean package -Daether.connector.https.securityMode=insecure
```

### References

* https://maven.apache.org/guides/mini/guide-http-settings.html
* https://maven.apache.org/guides/mini/guide-wagon-providers.html
* https://maven.apache.org/guides/mini/guide-resolver-transport.html
* https://maven.apache.org/resolver/third-party-integrations.html
* https://github.com/apache/maven-wagon
* https://github.com/apache/maven-resolver
