## Maven Artifact Resolver http 3 transport

This plugin based on standard maven artifact resolver http plugin. It currently uses Eclipse Jetty http3 client.
Usage:
```
<project .... >
...
  <build>
    <extensions>
      <extension>
        <groupId>com.artipie.maven.resolver</groupId>
        <artifactId>maven-resolver-transport-http3</artifactId>
        <version>0.0.1</version>
      </extension>
    </extensions>
  </build>
</project>
```

Plugin factory called only on first usage. For maven 3.9+ artifact resolver transport is default:

```
# explicit switch to Resolver API, force skip certificates check
time mvn -Dmaven.resolver.transport=native -Daether.connector.https.securityMode=insecure clean package
```

Currently test require test http3 server. See `mvn-http3/README.md` (parent directory) for details.
```shell
cd mvn-http3
caddy run # http3 test server
```
