#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
if [ ! -f target/pomosh-aktivistu-0.1.0.jar ]; then
  mvn package
fi
exec java -jar target/pomosh-aktivistu-0.1.0.jar
