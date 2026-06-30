# GitHub Secrets Environment Sync

This project stores production backend environment values in GitHub Actions
Secrets and syncs them into the Render backend service.

## Flow

```text
GitHub Secrets -> GitHub Actions -> Render service env vars -> Render Docker build/deploy
```

## Required GitHub Secrets

Add these in:

```text
GitHub repository -> Settings -> Secrets and variables -> Actions -> New repository secret
```

```text
RENDER_API_KEY
RENDER_SERVICE_ID
SPRING_PROFILES_ACTIVE
DB_URL
DB_USER
DB_PASS
ALLFOLIO_JWT_SECRET
ALLFOLIO_ENCRYPTION_KEY
ACCESS_TOKEN_MINUTES
REFRESH_TOKEN_DAYS
ALLOWED_ORIGINS
KAFKA_ENABLED
```

## Value Guide

Use these values for the Render + Neon setup. Keep real infrastructure values
only in GitHub Secrets and Render environment variables.

```text
RENDER_API_KEY=<Render API key from Render Account Settings>
RENDER_SERVICE_ID=<Render backend service id, starts with srv->
SPRING_PROFILES_ACTIVE=prod
DB_URL=jdbc:postgresql://<neon-host>/<database>?sslmode=require&channel_binding=require
DB_USER=<neon-user>
DB_PASS=<Neon database password from your connection string>
ALLFOLIO_JWT_SECRET=<32+ byte random secret>
ALLFOLIO_ENCRYPTION_KEY=<base64 32-byte AES key>
ACCESS_TOKEN_MINUTES=15
REFRESH_TOKEN_DAYS=30
ALLOWED_ORIGINS=https://<your-vercel-domain>
KAFKA_ENABLED=false
```

Generate `ALLFOLIO_JWT_SECRET` locally:

```bash
openssl rand -base64 48
```

Generate `ALLFOLIO_ENCRYPTION_KEY` locally:

```bash
openssl rand -base64 32
```

The Render service ID is visible in the Render service URL:

```text
https://dashboard.render.com/web/srv-xxxxxxxxxxxx
```

In that example:

```text
RENDER_SERVICE_ID=srv-xxxxxxxxxxxx
```

## Deploy

After adding or changing secrets, run:

```text
GitHub Actions -> Sync Render Environment -> Run workflow -> deploy: true
```

On push to `main`, the same workflow also runs automatically for backend or
Render config changes.

## Local Build

Local builds are only for verification. You do not upload a local JAR or Docker
image to Render.

```bash
cd allfolio-backend
./gradlew :backend-app:bootJar -x test --no-daemon
```

Render builds and deploys from the GitHub commit after the workflow updates the
service environment variables.

## Optional Integration Secrets

Do not add these until the app actually needs them:

```text
FSC_API_KEY
MORALIS_API_KEY
BINANCE_API_KEY
BINANCE_API_SECRET
KIS_APP_KEY
KIS_APP_SECRET
KIWOOM_APP_KEY
KIWOOM_APP_SECRET
SAMSUNG_APP_KEY
SAMSUNG_APP_SECRET
TOSS_CLIENT_ID
TOSS_CLIENT_SECRET
```
