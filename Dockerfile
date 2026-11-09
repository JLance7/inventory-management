FROM eclipse-temurin:11-jdk-jammy AS build
WORKDIR /build
ADD https://repo.maven.apache.org/maven2/com/google/code/gson/gson/2.13.2/gson-2.13.2.jar /build/lib/gson.jar
ADD https://repo.maven.apache.org/maven2/org/mariadb/jdbc/mariadb-java-client/3.5.11/mariadb-java-client-3.5.11.jar /build/lib/mariadb.jar
RUN echo "dd0ce1b55a3ed2080cb70f9c655850cda86c206862310009dcb5e5c95265a5e0  lib/gson.jar" | sha256sum -c - \
 && echo "42604178b9e57ec08ee0035079961516f086e455d0141f0b22c6eff29fb2fff4  lib/mariadb.jar" | sha256sum -c -
COPY src/main/java/inventory/Models.java src/main/java/inventory/Database.java src/main/java/inventory/Server.java ./src/inventory/
RUN mkdir classes && javac --release 11 -encoding UTF-8 -cp 'lib/*' -d classes src/inventory/*.java \
 && jar --create --file inventory-server.jar --main-class inventory.Server -C classes .
FROM eclipse-temurin:11-jre-jammy
WORKDIR /app
COPY --from=build /build/inventory-server.jar ./inventory-server.jar
COPY --from=build /build/lib ./lib
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-cp", "inventory-server.jar:lib/*", "inventory.Server"]
