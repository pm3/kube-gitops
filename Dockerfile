FROM openjdk:21-jdk-slim as build

WORKDIR /app

RUN jlink --add-modules java.base,java.xml,java.desktop,java.security.jgss,jdk.crypto.ec,jdk.localedata,java.net.http,jdk.httpserver --include-locales en --output jre

FROM debian:buster-slim

COPY --from=build /app/jre /usr/jre/

RUN apt-get update && apt-get install -y curl tzdata git openssh-client && rm -rf /var/cache /var/lib/apt /var/lib/dpkg /var/log/apt \
 && git config --global pull.rebase true \
 && curl -L -o /usr/bin/kubectl https://storage.googleapis.com/kubernetes-release/release/`curl -s https://storage.googleapis.com/kubernetes-release/release/stable.txt`/bin/linux/amd64/kubectl \
 && chmod 755 /usr/bin/kubectl \
 && addgroup --gid 1000 app && adduser --home /app --uid 1000 --gid 1000 --disabled-password --no-create-home --gecos '' app \
 && mkdir -p /app/data && chown 1000:1000 /app /app/data

COPY target/kube-gitops-*-jar-with-dependencies.jar /app/app.jar

WORKDIR /app
USER 1000:1000
ENV LANG=C.UTF-8 TZ=Europe/Bratislava JAVA_HOME=/usr/jre PATH=$PATH:/usr/jre/bin
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
