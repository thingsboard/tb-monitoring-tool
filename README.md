# env-status-test
Thingsboard environment status test

The project that is able to check the status of the Thingsboard environment by sending the messages for a specified number of devices and expect them to be processed within a given period of time.

## Prerequisites

- [Install Docker CE](https://docs.docker.com/engine/installation/)

## Running

To run test against ThingsBoard first create plain text file to set up test configuration (in our example configuration file name is *.env*):
```bash
touch .env
```

Edit this *.env* file:
```bash
nano .env
```

and put next content into the text file (modify it according to your test goals):
```bash
REST_URL=http://IP_ADDRESS_OF_TB_INSTANCE:9090
# IP_ADDRESS_OF_TB_INSTANCE is your local IP address if you run ThingsBoard on your dev machine in docker
# Port should be modified as well if needed 
REST_WEB_SOCKET_URL=ws://IP_ADDRESS_OF_TB_INSTANCE:9090/api/ws/plugins/telemetry?token=
REST_USERNAME=tenant@thingsboard.org
REST_PASSWORD=tenant

MQTT_HOST=IP_ADDRESS_OF_TB_INSTANCE
# IP_ADDRESS_OF_TB_INSTANCE is your local IP address if you run ThingsBoard on your dev machine in docker
MQTT_PORT=1883

DEVICE_API=HTTP
DEVICE_COUNT=3

PUBLISH_PAUSE=5000

PERFORMANCE_DURATION=3000

EMAIL_ALERT_EMAILS=YOUR_EMAIL_ADDRESSES
EMAIL_ALERT_PERIOD=1
EMAIL_STATUS_EMAIL=YOUR_EMAIL_ADDRESSES
EMAIL_STATUS_PERIOD=6
```

Where: 
    
- `REST_URL`                     - Rest URL of the TB instance
- `REST_WEB_SOCKET_URL`          - Web Socket URL of the TB instance
- `REST_USERNAME`                - Login of the user 
- `REST_PASSWORD`                - Password of the user
- `MQTT_HOST`                    - URL of the ThingsBoard MQTT broker
- `MQTT_PORT`                    - Port of the ThingsBoard MQTT broker
- `DEVICE_API`                   - Use MQTT or HTTP Device API for send messages
- `DEVICE_COUNT`                 - Device count to which the messages will be sent
- `PUBLISH_PAUSE`                - Pause between messages for a single simulated device in milliseconds
- `PERFORMANCE_DURATION`         - Time for processing of a single message to determine whether the TB instance is working well in milliseconds
- `EMAIL_ALERT_EMAILS`           - Comma separated list of emails to send an alert in case of TB env troubles
- `EMAIL_ALERT_PERIOD`           - Time between sending the alert emails
- `EMAIL_STATUS_EMAIL`           - Comma separated list of emails to send a status of the script
- `EMAIL_STATUS_PERIOD`          - Time between sending the script status emails

  
Once params are configured to run test simple type from the folder where configuration file is located:
```bash
docker run -it --env-file .env --name tb-perf-test thingsboard/tb-performance-test
```
