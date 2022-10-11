# prometheus-bash-exporter

[![Docker Workflow](https://github.com/lhns/prometheus-bash-scraper/workflows/Docker/badge.svg)](https://github.com/lhns/prometheus-bash-scraper/actions?query=workflow%3ADocker)
[![Release Notes](https://img.shields.io/github/release/lhns/prometheus-bash-scraper.svg?maxAge=3600)](https://github.com/lhns/prometheus-bash-scraper/releases/latest)
[![Apache License 2.0](https://img.shields.io/github/license/lhns/prometheus-bash-scraper.svg?maxAge=3600)](https://www.apache.org/licenses/LICENSE-2.0)
[![Scala Steward badge](https://img.shields.io/badge/Scala_Steward-helping-blue.svg?style=flat&logo=data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAA4AAAAQCAMAAAARSr4IAAAAVFBMVEUAAACHjojlOy5NWlrKzcYRKjGFjIbp293YycuLa3pYY2LSqql4f3pCUFTgSjNodYRmcXUsPD/NTTbjRS+2jomhgnzNc223cGvZS0HaSD0XLjbaSjElhIr+AAAAAXRSTlMAQObYZgAAAHlJREFUCNdNyosOwyAIhWHAQS1Vt7a77/3fcxxdmv0xwmckutAR1nkm4ggbyEcg/wWmlGLDAA3oL50xi6fk5ffZ3E2E3QfZDCcCN2YtbEWZt+Drc6u6rlqv7Uk0LdKqqr5rk2UCRXOk0vmQKGfc94nOJyQjouF9H/wCc9gECEYfONoAAAAASUVORK5CYII=)](https://scala-steward.org)

Prometheus metric exporter that executes a bash script on every scrape and returns the metrics from stdout.
`$@` in the executed script will always contain the request path segments.

## Environment variables
- **LOG_LEVEL**: log level (default: ERROR)
- **SERVER_HOST**: listen host (default: 0.0.0.0)
- **SERVER_PORT**: listen port (default: 8080)
- **SCRIPT_METRICS**: script that is executed on `GET /metrics`
- **SCRIPT_TEST**: script that is executed on `GET /test`
- **CACHE_TEST**: cache the output of `GET /test` for 10 seconds

## Scripts

Head over to the [wiki](https://github.com/lhns/prometheus-bash-exporter/wiki) to see our collection of scripts.

## Docker builds
https://github.com/users/lhns/packages/container/package/prometheus-bash-exporter

## License
This project uses the Apache 2.0 License. See the file called LICENSE.
