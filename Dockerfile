FROM docker.io/bitnami/spark:3.3

USER root
RUN apt-get update && \
    apt-get install sudo && \
    apt-get install iproute2 iputils-ping -y