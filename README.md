# prometheus-bash-exporter
[![Docker Workflow](https://github.com/LolHens/prometheus-bash-scraper/workflows/Docker/badge.svg)](https://github.com/LolHens/prometheus-bash-scraper/actions?query=workflow%3ADocker)
[![Release Notes](https://img.shields.io/github/release/LolHens/prometheus-bash-scraper.svg?maxAge=3600)](https://github.com/LolHens/prometheus-bash-scraper/releases/latest)
[![Apache License 2.0](https://img.shields.io/github/license/LolHens/prometheus-bash-scraper.svg?maxAge=3600)](https://www.apache.org/licenses/LICENSE-2.0)

Prometheus metric exporter that executes a bash script on every scrape and returns the metrics from stdout.
```$1``` in the executed script will always contain the request path.

## Enviromnet variables
- **LOG_LEVEL**: log level (default: INFO)
- **SERVER_HOST**: listen host (default: 0.0.0.0)
- **SERVER_PORT**: listen port (default: 8080)
- **SCRIPT_METRICS**: script that is executed on `GET /metrics`
- **SCRIPT_TEST**: script that is executed on `GET /test`

## Docker builds
https://github.com/users/LolHens/packages/container/package/prometheus-bash-exporter

## License
This project uses the Apache 2.0 License. See the file called LICENSE.
