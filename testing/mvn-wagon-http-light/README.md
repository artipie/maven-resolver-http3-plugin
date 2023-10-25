## Maven Wagon http 1.x transport

This is distilled version of standard maven wagon http plugin for test purposes.
Usage:
```
<project .... >
...
  <build>
    <extensions>
      <extension>
        <groupId>com.artipie.maven.wagon</groupId>
        <artifactId>wagon-http-lightweight</artifactId>
        <version>0.0.1</version>
      </extension>
    </extensions>
  </build>
</project>
```

Plugin factory called only on first usage. For maven 3.9+ wagon transport must be enabled explicitly:

`time mvn -Dmaven.resolver.transport=wagon clean package`
