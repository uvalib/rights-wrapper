# builder
FROM public.ecr.aws/docker/library/amazoncorretto:8-al2022-jdk as builder

# update and install dependancies
RUN yum -y update && yum -y install maven

# copy assets
WORKDIR /build
COPY src ./src
COPY lib ./lib
COPY pom.xml ./

# manual install of an old jar file
ENV JAVA_HOME=/usr/lib/jvm/java-1.8.0-amazon-corretto.x86_64
RUN mvn install:install-file -Dfile=/build/lib/commons-imaging-1.0-20160119.072927-71.jar -DgroupId=org.apache.commons -DartifactId=commons-imaging -Dversion=1.0-JAVA-6 -Dpackaging=jar

# build the tartet WAR file
RUN mvn clean package

# base image
FROM public.ecr.aws/docker/library/jetty:9.4.50-jre8-alpine

# so we can install new packages
USER root

# install updates and dependancies
RUN apk update && apk upgrade && apk add imagemagick

# disable imagemagick policies
RUN rm -f /etc/ImageMagick*/policy.xml

# the default user for this base image
USER jetty

# port and run command
EXPOSE 8080

# copy the WAR file
COPY --from=builder /build/target/rights-wrapper.war /var/lib/jetty/webapps/ROOT.war

# Add the build tag
ENV APP_HOME /var/lib/jetty
ARG BUILD_TAG
RUN test -n "$BUILD_TAG" && touch $APP_HOME/buildtag.build-$BUILD_TAG || touch $APP_HOME/buildtag.build-0

#
# end of file
#
