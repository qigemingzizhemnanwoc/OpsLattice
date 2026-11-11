FROM node:22-alpine AS build
WORKDIR /build
COPY front/package*.json ./
RUN npm ci
COPY front/ ./
RUN npm run build
FROM nginx:stable-alpine
COPY deploy/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html
EXPOSE 80
