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

# Install Tesseract OCR and its dependencies
RUN apt-get update && \
    apt-get install -y tesseract-ocr tesseract-ocr-eng && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Find tessdata directory and verify it exists
# Common locations: /usr/share/tesseract-ocr/5/tessdata (Tesseract 5.x) or /usr/share/tesseract-ocr/4.00/tessdata (Tesseract 4.x)
RUN TESSDATA_DIR=$(find /usr/share/tesseract-ocr -type d -name "tessdata" 2>/dev/null | head -1) && \
    if [ -z "$TESSDATA_DIR" ]; then \
        TESSDATA_DIR="/usr/share/tesseract-ocr/5/tessdata"; \
    fi && \
    echo "TESSDATA_PREFIX=$TESSDATA_DIR" && \
    ls -la "$TESSDATA_DIR" || echo "Warning: tessdata directory may not exist at $TESSDATA_DIR"

# Set TESSDATA_PREFIX environment variable
# This will be used by Tesseract to find language data files
ENV TESSDATA_PREFIX=/usr/share/tesseract-ocr/5/tessdata

# Convert build arguments to environment variables
ENV DB_URI=${DB_URI}
ENV CLIENT_URL=${CLIENT_URL}

# copy the built jar from the app module
COPY --from=build /build/app/target/*.jar app.jar

EXPOSE 8080

# Use exec form to ensure environment variables are passed correctly
ENTRYPOINT ["java", "-jar", "app.jar"]
