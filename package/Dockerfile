# builder
FROM openjdk:8 as builder

# update and install dependancies
RUN apt-get -y update && apt-get -y install maven

# copy assets
WORKDIR /build
COPY src ./src
COPY lib ./lib
COPY pom.xml ./

# manual install of an old jar file
RUN mvn install:install-file -Dfile=/build/lib/commons-imaging-1.0-20160119.072927-71.jar -DgroupId=org.apache.commons -DartifactId=commons-imaging -Dversion=1.0-JAVA-6 -Dpackaging=jar

# build the tartet WAR file
RUN mvn clean package

# base image
FROM tomcat:8.0

# install updates and dependancies
RUN apt-get -y update && apt-get -y install imagemagick

# port and run command
EXPOSE 8080

# copy the WAR file
COPY --from=builder /build/target/rights-wrapper.war /usr/local/tomcat/webapps/

# add the healthcheck
RUN mkdir /usr/local/tomcat/webapps/healthcheck && echo "OK" > /usr/local/tomcat/webapps/healthcheck/index.html

#
# end of file
#
