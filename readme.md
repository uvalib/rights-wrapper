# rightswrapper2

A simple web service that adds a wrapper around images conveying
terms of use and bibliographic information.  Since being renamed to
rightswrapper2, this service determines the text from a solr index
record and enforces basic access controls (again, based on the 
content of the index record).

To deploy this servlet:

1. Be sure that ImageMagick is installed and the "convert" command is on the path for the user under which the servlet container is running.
2. Update src/main/webapp/WEB-INF/web.xml to replace the context parameter values with those appropriate for your deployment environment
3. Invoke "mvn clean package"
4. Deploy "target/rightswrapper2.war" in your favorite servlet container

To build this for java 6, you must first locally install an old snapshot version of commons-imaging by typing:

```
mvn install:install-file -Dfile=lib/commons-imaging-1.0-20160119.072927-71.jar -DgroupId=org.apache.commons -DartifactId=commons-imaging -Dversion=1.0-JAVA-6 -Dpackaging=jar
```


