ARG NODE_VERSION=25.2.1
FROM node:${NODE_VERSION}-alpine as base
WORKDIR /usr/src/app

################################################################################
FROM base as deps
RUN --mount=type=bind,source=package.json,target=package.json \
    --mount=type=bind,source=package-lock.json,target=package-lock.json \
    --mount=type=cache,target=/root/.npm \
    npm ci --omit=dev

################################################################################
FROM deps as build
RUN --mount=type=bind,source=package.json,target=package.json \
    --mount=type=bind,source=package-lock.json,target=package-lock.json \
    --mount=type=cache,target=/root/.npm \
    npm ci
COPY . .
RUN npm run build

################################################################################
FROM base as final
ENV NODE_ENV production
USER node

# 1. Copiamos package.json
COPY package.json .

# 2. Copiamos las dependencias de producción (sin la CLI de nest)
COPY --from=deps /usr/src/app/node_modules ./node_modules

# 3. ¡IMPORTANTE! Copiamos la carpeta DIST (el código compilado), NO la carpeta SRC
COPY --from=build /usr/src/app/dist ./dist

EXPOSE 8086

# 4. Ejecutamos directamente con NODE (esto es más rápido y seguro)
CMD ["node", "dist/main"]