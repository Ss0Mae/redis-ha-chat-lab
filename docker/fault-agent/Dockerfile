FROM alpine:3.20
RUN apk add --no-cache iptables iproute2
COPY faults /faults
RUN chmod +x /faults/*.sh
CMD ["sleep", "infinity"]
