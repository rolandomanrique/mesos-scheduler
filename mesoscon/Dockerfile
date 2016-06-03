FROM ubuntu:14.04

RUN echo "deb http://repos.mesosphere.io/ubuntu/ trusty main" > /etc/apt/sources.list.d/mesosphere.list && \
  apt-key adv --keyserver keyserver.ubuntu.com --recv E56151BF && \
  apt-get -y update && \
  apt-get -y install mesos=0.25.* && \
  apt-get clean && rm -rf /var/lib/apt/lists/*

VOLUME /opt/app

WORKDIR /opt/app

ENTRYPOINT java -jar mesoscon-assembly-*.jar
