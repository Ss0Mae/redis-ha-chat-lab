FROM eclipse-temurin:21-jre
WORKDIR /app
COPY build/libs/redis-ha-chat-lab-*.jar app.jar
ENV JAVA_OPTS="-Xms512m -Xmx512m -XX:+UseG1GC"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
