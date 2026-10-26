# ONEOFFICEAUTOMATION Command Center as a Linux container (C1).
# Build from the customer distribution (no vendor tools):
#   gradlew :cli:installCustomerDist
#   docker build -f docker/Dockerfile -t ooa-command-center:0.1.1 .        (from the platform folder)
# Run (data in a volume; the vault key from your secret store):
#   docker run -d -p 8720:8720 -v ooa-data:/data -e OOA_MASTER_KEY=... ooa-command-center:0.1.1
# With SQL Server: -e OOA_DB_URL="jdbc:sqlserver://sql:1433;databaseName=ooa_command_center;encrypt=true"
#   -e OOA_DB_USER=ooa_app -e OOA_DB_PASSWORD=...   (see docker/compose.yaml and docs/CLOUD_INSTALL.md)
FROM eclipse-temurin:21-jre-jammy

LABEL org.opencontainers.image.title="ONEOFFICEAUTOMATION Command Center" \
      org.opencontainers.image.vendor="ONEOFFICEAUTOMATION"

# a user of its own; the program read-only, the data in /data
RUN groupadd --system ooa && useradd --system --gid ooa --home-dir /data --shell /usr/sbin/nologin ooa \
    && mkdir -p /opt/ooa /data && chown ooa:ooa /data
COPY --chown=root:root cli/build/install/ooa-customer /opt/ooa
COPY --chown=root:root --chmod=755 docker/docker-entrypoint.sh /opt/ooa/docker-entrypoint.sh
RUN chmod 755 /opt/ooa/bin/ooa

USER ooa
VOLUME ["/data"]
EXPOSE 8720
ENV OOA_PORT=8720 JAVA_OPTS="-Xmx2g -XX:+ExitOnOutOfMemoryError -Djava.awt.headless=true"
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/${OOA_PORT} && printf "GET /api/health HTTP/1.0\r\n\r\n" >&3 && grep -q " 200 " <&3'
ENTRYPOINT ["/opt/ooa/docker-entrypoint.sh"]
