FROM node:24-alpine AS build
WORKDIR /app
COPY services/console/package*.json ./
RUN npm ci
COPY services/console/index.html services/console/tsconfig.json ./
COPY services/console/src ./src
RUN npm run build
FROM node:24-alpine
ENV NODE_ENV=production SENTINEL_CONSOLE_BIND=0.0.0.0
WORKDIR /app/services/console
COPY --from=build /app/dist ./dist
COPY services/console/server.mjs ./
COPY policies/examples /app/policies/examples
USER node
EXPOSE 3000
CMD ["node", "server.mjs"]
