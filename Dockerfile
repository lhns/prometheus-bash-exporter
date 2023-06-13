FROM debian:12

ENV CLEANIMAGE_VERSION 2.0
ENV CLEANIMAGE_URL https://raw.githubusercontent.com/LolHens/docker-cleanimage/$CLEANIMAGE_VERSION/cleanimage

ADD ["$CLEANIMAGE_URL", "/usr/local/bin/"]
RUN chmod +x "/usr/local/bin/cleanimage"

ENV JQ_REF a17dd32
ENV JQ_URL https://github.com/LolHens/jq-buildenv/releases/download/$JQ_REF/jq

RUN apt-get update \
 && apt-get install -y \
      curl \
 && curl -LsSf -- "$JQ_URL" > /usr/bin/jq \
 && chmod +x /usr/bin/jq \
 && cleanimage

COPY prometheus-bash-exporter ./

HEALTHCHECK --interval=15s --timeout=3s --start-period=10s \
  CMD curl -Ssf -- http://localhost:${SERVER_PORT:-8080}/health || exit 1

CMD exec ./prometheus-bash-exporter
