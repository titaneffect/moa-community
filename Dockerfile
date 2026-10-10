FROM tomcat:9.0-jdk8-temurin
COPY deploy/tomcat/ROOT.xml /usr/local/tomcat/conf/Catalina/localhost/ROOT.xml
COPY target/*.war /usr/local/tomcat/webapps/ROOT.war
