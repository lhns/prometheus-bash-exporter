FROM lolhens/sbt-graal:graal-20.3.0-java11 as builder
MAINTAINER LolHens <pierrekisters@gmail.com>
COPY . .
ARG CI_VERSION=
RUN sbt graalvm-native-image:packageBin
RUN cp target/graalvm-native-image/prometheus-bash-exporter* prometheus-bash-exporter

FROM debian:10

ENV CLEANIMAGE_VERSION 2.0
ENV CLEANIMAGE_URL https://raw.githubusercontent.com/LolHens/docker-cleanimage/$CLEANIMAGE_VERSION/cleanimage

ADD ["$CLEANIMAGE_URL", "/usr/local/bin/"]
RUN chmod +x "/usr/local/bin/cleanimage"

ENV JQ_VERSION 1.6
ENV JQ_URL https://github.com/stedolan/jq/releases/download/jq-$JQ_VERSION/jq-linux64

RUN apt-get update \
 && apt-get install -y \
      curl \
 && curl -LSsf -- "$JQ_URL" > /usr/bin/jq \
 && chmod +x /usr/bin/jq \
 && cleanimage

COPY --from=builder /root/prometheus-bash-exporter .

HEALTHCHECK --interval=10s --timeout=3s --start-period=10s \
  CMD curl -Ssf -- http://localhost:${SERVER_PORT:-8080}/health || exit 1

CMD exec ./prometheus-bash-exporter
