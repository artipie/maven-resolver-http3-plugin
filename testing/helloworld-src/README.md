HelloWorld
==========

A simple Java application for test purposes that can be compiled into a .jar file using Maven.

To build
--------
    mvn clean package

To run
--------
    java -cp target/helloworld-1.0.jar com.artipie.helloworld.HelloWorld

Maven test repository settings
--------
See `./mvn/settings.xml`

Trigger Maven Transport
--------
    rm -rf $HOME/.m2/repository/commons-cli/commons-cli/1.4
    time mvn -V -Daether.connector.https.securityMode=insecure -Dmaven.resolver.transport=native clean package # native or wagon, see pom.xml

