FROM node:22-alpine AS builder

WORKDIR /app
COPY services/messaging/package*.json ./
RUN npm ci

COPY services/messaging/ .
RUN npm run build

FROM node:22-alpine

WORKDIR /app
COPY --from=builder /app/dist ./dist
COPY --from=builder /app/node_modules ./node_modules
COPY services/messaging/package*.json ./

EXPOSE 3001

CMD ["node", "dist/main"]
