#
# Copyright © 2016-2026 The Thingsboard Authors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# Build the jar first (from repo root): mvn package -DskipTests
# Then: docker build -f docker/Dockerfile -t tb-monitoring .
# Multi-arch (amd64/arm64) - the jar is pure JVM bytecode, only the base image needs both platforms:
#   docker buildx build --platform linux/amd64,linux/arm64 -f docker/Dockerfile -t <repo>:tag --push .

FROM thingsboard/openjdk25:trixie-slim

COPY target/tb-monitoring-*.jar /app/tb-monitoring.jar

ENTRYPOINT ["java", "-jar", "/app/tb-monitoring.jar"]
