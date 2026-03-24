# -------- Build stage --------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# copy everything (multi-module project)
COPY . .

# embed commit ID into app so GET /commit.txt returns deployed commit (CI writes commit.txt before build)
RUN mkdir -p app/src/main/resources/static && \
    (test -f commit.txt && cp commit.txt app/src/main/resources/static/commit.txt) || \
    echo "local-build" > app/src/main/resources/static/commit.txt

# build only the app module and its dependencies
RUN mvn -pl app -am clean package -DskipTests
# if your main module isn't "app", replace "app" above and below with that module name.


# -------- Runtime stage --------
FROM eclipse-temurin:21-jre

#COPY --from=grafana/alloy:v1.7.5 /bin/alloy /usr/local/bin/alloy

WORKDIR /app

# Convert build arguments to environment variables
ENV DB_URI=${DB_URI}
ENV CLIENT_URL=${CLIENT_URL}
ENV OPENAI_API_KEY=${OPENAI_API_KEY}
ENV AWS_ACCESS_KEY=${AWS_ACCESS_KEY}
ENV AWS_SECRET_ACCESS=${AWS_SECRET_ACCESS}
ENV AWS_REGION=${AWS_REGION}
ENV MESSAGING_EMAIL_ENABLED=${MESSAGING_EMAIL_ENABLED}
ENV MESSAGING_DISPATCH_INTERVAL_MS=${MESSAGING_DISPATCH_INTERVAL_MS}
ENV RESEND_API_KEY=${RESEND_API_KEY}
ENV RESEND_FROM_EMAIL=${RESEND_FROM_EMAIL}
ENV RESEND_FROM_NAME=${RESEND_FROM_NAME}

#COPY grafana/config.alloy /etc/alloy/config.alloy
#COPY grafana/entrypoint.sh /entrypoint.sh
#RUN chmod +x /entrypoint.sh

# copy the built jar from the app module (commit.txt is inside as static resource)
COPY --from=build /build/app/target/*.jar app.jar

EXPOSE 8080

#ENTRYPOINT ["/entrypoint.sh"]

ENTRYPOINT ["java", "-jar", "app.jar"]
