## Maven Artifact Resolver http 1.x transport

This is distilled version of standard maven artifact resolver http plugin for test purposes.
Usage:
```
<project .... >
...
  <build>
    <extensions>
      <extension>
        <groupId>com.artipie.maven.resolver</groupId>
        <artifactId>maven-resolver-transport-http</artifactId>
        <version>0.0.1</version>
      </extension>
    </extensions>
  </build>
</project>
```

Plugin factory called only on first usage. For maven 3.9+ artifact resolver transport is default:

```
time mvn clean package
time mvn -Dmaven.resolver.transport=native clean package
```
