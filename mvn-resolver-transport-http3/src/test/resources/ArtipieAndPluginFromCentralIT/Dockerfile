# Ubuntu 16.04
# Oracle Java 21 64 bit
# Maven 3.9.5

FROM ubuntu:22.04

# this is a non-interactive automated build - avoid some warning messages
ENV DEBIAN_FRONTEND noninteractive

# update dpkg repositories
RUN apt-get update

# install wget
RUN apt-get install -y wget

# get maven 3.3.9
RUN wget --no-verbose -O /tmp/apache-maven-3.9.5.tar.gz https://dlcdn.apache.org/maven/maven-3/3.9.5/binaries/apache-maven-3.9.5-bin.tar.gz

# install maven
RUN tar xzf /tmp/apache-maven-3.9.5.tar.gz -C /opt/
RUN ln -s /opt/apache-maven-3.9.5 /opt/maven
RUN ln -s /opt/maven/bin/mvn /usr/local/bin
RUN rm -f /tmp/apache-maven-3.9.5.tar.gz
ENV MAVEN_HOME /opt/maven

# remove download archive files
RUN apt-get clean

# set shell variables for java installation
ENV java_version 21
ENV filename jdk-21_linux-x64_bin.tar.gz
ENV downloadlink https://download.oracle.com/java/21/latest/$filename

# download java, accepting the license agreement
RUN wget --no-cookies --header "Cookie: oraclelicense=accept-securebackup-cookie" -O /tmp/$filename $downloadlink

# unpack java
RUN mkdir /opt/java-oracle/ && mkdir /opt/java-oracle/jdk21/ && tar -zxf /tmp/$filename -C /opt/java-oracle/jdk21/ --strip-components 1
ENV JAVA_HOME /opt/java-oracle/jdk21
ENV PATH $JAVA_HOME/bin:$PATH

# configure symbolic links for the java and javac executables
RUN update-alternatives --install /usr/bin/java java $JAVA_HOME/bin/java 20000 && update-alternatives --install /usr/bin/javac javac $JAVA_HOME/bin/javac 20000

# check maven
RUN mvn --version

CMD [""]