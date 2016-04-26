FROM registry.docker.aws-us-west-2-infrastructure.socrata.net:5000/internal/secondary-watcher:3.0.1_6329_11316ad4

# TODO expose JMX
# EXPOSE

ENV SECONDARY_ARTIFACT secondary-watcher-geocoding-assembly.jar
ENV SECONDARY_CONFIG secondary.conf

# defaults
ENV GEOCODING_SECONDARY_NUM_WORKERS 5
ENV GEOCODING_SECONDARY_WORK_MEM 768MB

WORKDIR $SERVER_ROOT

COPY ${SECONDARY_CONFIG}.j2 $SERVER_ROOT/
COPY $SECONDARY_ARTIFACT $SERVER_ROOT/plugins/