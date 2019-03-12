#
# Copyright © 2016-2018 The Thingsboard Authors
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

FROM openjdk:8-jdk

COPY start-tests.sh ${pkg.name}.conf ${pkg.name}.deb /tmp/

RUN chmod a+x /tmp/*.sh \
    && mv /tmp/start-tests.sh /usr/bin

RUN dpkg -i /tmp/${pkg.name}.deb

RUN update-rc.d ${pkg.name} disable

RUN mv /tmp/${pkg.name}.conf ${pkg.installFolder}/conf

ENV REST_URL=localhost:9090
ENV REST_WEB_SOCKET_URL=ws://localhost:9090/api/ws/plugins/telemetry?token
ENV REST_USERNAME=tenant@thingsboard.org
ENV REST_PASSWORD=tenant

ENV MQTT_HOST=localhost
ENV MQTT_PORT=1883

ENV DEVICE_API=HTTP
ENV DEVICE_COUNT=3

ENV PUBLISH_PAUSE=5000

ENV PERFORMANCE_DURATION=3000

ENV EMAIL_ALERT_EMAILS=dshvaika@thingsboard.io,dlandiak@thingsboard.io
ENV EMAIL_ALERT_PERIOD=1
ENV EMAIL_STATUS_EMAIL=dlandiak@thingsboard.io
ENV EMAIL_STATUS_PERIOD=6

CMD ["start-tests.sh"]