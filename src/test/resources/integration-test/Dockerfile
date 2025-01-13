FROM maven:3.8.7-openjdk-18-slim

ARG HOST_UID

ENV HOME=/home/builduser
ENV JAVA_TOOL_OPTIONS="-javaagent:$HOME/aws-sdk-cert-trust-agent.jar"
ENV MAVEN_HOME=$HOME/.m2

# Create a user with the specified UID and GID
RUN useradd -m -u ${HOST_UID} -s /bin/bash builduser -m

COPY test-projects $HOME/test-projects
COPY aws-sdk-cert-trust-agent.jar $HOME
COPY m2-cache $HOME/.m2/repository
# https://github.com/localstack/aws-java-sdk-v2-trust-certs-patch

WORKDIR $HOME
RUN chown -R builduser: $HOME
# reset the entrypoint from the base container
ENTRYPOINT []

USER builduser