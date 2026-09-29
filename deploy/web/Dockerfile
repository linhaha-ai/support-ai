FROM node:24-alpine@sha256:ebfe2f90462722a7a4de65e91990e97fe0d401c70e0e762c5b53302f905ec1c1 AS builder

WORKDIR /workspace

COPY frontend/package.json frontend/package-lock.json ./
RUN npm install --global npm@11.8.0 \
    && npm ci

COPY frontend ./
RUN npm run build

FROM nginx:1.28-alpine@sha256:a8b39bd9cf0f83869a2162827a0caf6137ddf759d50a171451b335cecc87d236

COPY deploy/web/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /workspace/dist /usr/share/nginx/html

EXPOSE 80
