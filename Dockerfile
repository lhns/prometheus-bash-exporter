FROM lolhens/sbt-graal:graal-20.3.0-java11 as builder
MAINTAINER LolHens <pierrekisters@gmail.com>
COPY . .
RUN sbt graalvm-native-image:packageBin
RUN cp target/graalvm-native-image/prometheus-bash-exporter* prometheus-bash-exporter

FROM debian:10
COPY --from=builder /root/prometheus-bash-exporter .
CMD exec ./prometheus-bash-exporter
