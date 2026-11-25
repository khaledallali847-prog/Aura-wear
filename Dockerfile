FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY Main.java ./
COPY static ./static
RUN javac -encoding UTF-8 Main.java
ENV PORT=8080 DATA_DIR=/app/data
EXPOSE 8080
CMD ["java", "-cp", ".", "Main"]
