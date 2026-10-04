# File Drop: the upload server, serving the Android app built from
# src/android/ in the same image. bin/build builds it, bin/run runs it.
#
#   docker build -t file-drop .
#   docker run --rm --network host -v "$PWD/data:/app/data" file-drop
#
# Files land in /app/data. PORT changes the port (tcp for http, udp for the
# app's discovery). The container runs as the unprivileged "node" user (uid
# 1000); bin/run passes your own uid instead.

# ---- stage 1: the apk (JDK 17 + Android build-tools, downloaded by bin/configure)

FROM debian:bookworm-slim AS apk

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl unzip zip \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app/src/android
# The toolchain layer only depends on bin/configure, so a source change
# rebuilds the apk without downloading the toolchain again.
COPY src/android/bin/configure bin/configure
RUN bin/configure
COPY src/android/ ./
RUN bin/build

# ---- stage 2: the server

FROM node:24-bookworm-slim

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --omit=dev && npm cache clean --force

COPY src/helpers/ src/helpers/
COPY src/http/ src/http/
COPY --from=apk /app/src/android/file-drop.apk src/http/public/file-drop.apk
COPY --from=apk /app/src/android/apk-version.txt src/http/public/apk-version.txt

RUN mkdir -p data && chown node:node data

USER node
ENV PORT=8080
EXPOSE 8080/tcp 8080/udp
VOLUME /app/data
CMD ["node", "/app/src/http/index.js"]
