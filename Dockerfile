# Use Amazon Corretto as the base image (compatible with Java 17)
FROM amazoncorretto:17-alpine

# Install necessary tools
RUN apk add --no-cache curl openssh

# Set up SSH for debugging
RUN ssh-keygen -A
RUN echo "root:password" | chpasswd
RUN sed -i 's/#PermitRootLogin prohibit-password/PermitRootLogin yes/' /etc/ssh/sshd_config

# Set the working directory in the container
WORKDIR /app

# Copy the jar file into the container
COPY target/valkey-store-1.0-SNAPSHOT.jar app.jar

# Expose the ports your application uses
EXPOSE 8080
EXPOSE 22

# Create a start script
RUN echo '#!/bin/sh' > /start.sh && \
    echo 'java -jar app.jar &' >> /start.sh && \
    echo '/usr/sbin/sshd -D' >> /start.sh && \
    chmod +x /start.sh

# Set the entrypoint to run your application and SSH server
ENTRYPOINT ["/start.sh"]
