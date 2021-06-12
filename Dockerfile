FROM alpine:3.12 AS builder
WORKDIR /retro-game-src
COPY . .
RUN \
  # Install packages needed to build the game.
  apk update && \
  apk --no-cache add \
    cmake \
    gcc \
    make \
    maven \
    musl-dev \
    openjdk11-jdk && \
  # Build the battle engine.
  JAVA_HOME=/usr/lib/jvm/java-11-openjdk cmake -B build -DCMAKE_BUILD_TYPE=Release battle-engine && \
  cmake --build build && \
  # Build the game.
  mvn -B -DskipTests package

FROM alpine:3.12
WORKDIR /retro-game
COPY --from=0 /retro-game-src/build/libBattleEngine.so .
COPY --from=0 /retro-game-src/target/retro-game-*.jar retro-game.jar
RUN \
  # Install packages needed to run the game.
  apk update && \
  apk --no-cache add openjdk11-jre-headless && \
  # Change the permissions of the artifacts.
  chmod 400 *
CMD ["java", "-Djava.library.path=.", "-jar", "retro-game.jar"]
