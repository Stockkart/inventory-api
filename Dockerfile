# -------- Build stage --------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# copy everything (multi-module project)
COPY . .

# build only the app module and its dependencies
RUN mvn -pl app -am clean package -DskipTests
# if your main module isn't "app", replace "app" above and below with that module name.


# -------- Runtime stage --------
FROM eclipse-temurin:21-jre
WORKDIR /app

# copy the built jar from the app module
COPY --from=build /build/app/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
