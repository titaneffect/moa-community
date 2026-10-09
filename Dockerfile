FROM tomcat:9.0-jdk8-temurin
COPY target/*.war /usr/local/tomcat/webapps/ROOT.war