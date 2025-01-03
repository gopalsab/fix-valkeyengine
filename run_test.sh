#!/bin/bash

# Start your Java application in the background
java -jar app.jar &

# Wait for your application to start (adjust the sleep time as needed)
sleep 30

# Run the JMeter test
/opt/jmeter/bin/jmeter -n -t /app/valkey_performance_test.jmx -l /app/results.jtl -e -o /app/report

# Keep the container running
tail -f /dev/null