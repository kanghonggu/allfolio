# Free Deployment Guide

## Target Stack

- Frontend: Vercel
- Backend: Render Free Web Service
- Database: Neon Free Postgres
- Auth: Allfolio local JWT auth

## Neon

1. Create a Neon project.
2. Copy the pooled JDBC connection details from **Connect**.
3. Convert the connection string for Spring Boot:

```text
postgresql://USER:PASSWORD@HOST/DB?sslmode=require
```

to:

```text
DB_URL=jdbc:postgresql://HOST/DB?sslmode=require
DB_USER=USER
DB_PASS=PASSWORD
```

4. Run `allfolio-backend/infra/postgres/init.sql` against the Neon database before first login.

## Render

Use `.env.render.example` as the checklist for Render dashboard variables. Do not commit a real `.env.render`.

For the current lightweight setup, follow `docs/GITHUB_SECRETS_ENV.md`.
GitHub Actions syncs repository secrets into Render and then triggers a Render
deploy.

Create a Web Service:

```text
Runtime: Docker
Dockerfile Path: allfolio-backend/backend-app/Dockerfile
Docker Context: repository root
Plan: Free
Health Check Path: /actuator/health
```

Set environment variables:

```text
SPRING_PROFILES_ACTIVE=prod
DB_URL=jdbc:postgresql://HOST/DB?sslmode=require
DB_USER=USER
DB_PASS=PASSWORD
ALLFOLIO_JWT_SECRET=<32+ byte random secret>
APP_ENCRYPTION_KEY=<base64 32-byte AES key>
ACCESS_TOKEN_MINUTES=15
REFRESH_TOKEN_DAYS=30
KAFKA_ENABLED=false
ALLOWED_ORIGINS=https://<your-vercel-domain>
```

Generate the JWT secret locally:

```bash
openssl rand -base64 48
```

Generate the database encryption key locally:

```bash
openssl rand -base64 32
```

## Vercel

Use `frontend/allfolio_app/.env.local.example` as the frontend template.

Set:

```text
NEXT_PUBLIC_API_BASE_URL=https://<render-service>.onrender.com
```

After custom domain setup, change it to:

```text
NEXT_PUBLIC_API_BASE_URL=https://api.<your-domain>
```

## Route53

Keep Route53 as the DNS host.

```text
allfolio root/www -> Vercel records
api.<domain>     -> CNAME <render-service>.onrender.com
```

Neon does not need a public custom DNS record; only the backend should use the Neon connection string.

## First Smoke Test

1. Open `/login`.
2. Enter email and an 8+ character password.
3. Click `회원가입`.
4. Confirm redirect to `/unified`.
5. Refresh the browser and confirm the session is restored.
6. Confirm Render logs show requests authenticated with Allfolio JWT.

## Local Build vs Render Deploy

Local builds are for verification only:

```bash
cd allfolio-backend
./gradlew :backend-app:bootJar -x test --no-daemon
```

Render still builds the Docker image from the GitHub commit. Push to `main` or
manually run the **Sync Render Environment** GitHub Action; the action reads
GitHub Secrets, updates Render env vars, and triggers the Render deploy.
