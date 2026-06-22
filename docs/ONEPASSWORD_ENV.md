# 1Password Environment Sync

This project keeps production secrets in 1Password and syncs them into the
Render backend service from GitHub Actions.

## Flow

```text
1Password -> GitHub Actions -> Render service env vars -> Render Docker build/deploy
```

Only one GitHub secret is required:

```text
OP_SERVICE_ACCOUNT_TOKEN
```

The Render API key, Render service ID, Neon credentials, JWT secret, and
integration keys are stored in 1Password and loaded by
`.github/workflows/sync-render-env.yml`.

If `OP_SERVICE_ACCOUNT_TOKEN` has not been added to GitHub yet, the workflow
prints setup guidance and skips the Render sync instead of failing the push.

## 1Password Setup

Create a 1Password vault or item structure that matches `.env.1password.example`,
or edit the workflow secret references to match your existing names.

Recommended item names:

```text
allfolio-prod/render
allfolio-prod/neon
allfolio-prod/allfolio
allfolio-prod/vercel
allfolio-prod/integrations
```

Required fields:

```text
render/api-key
render/backend-service-id
neon/jdbc-url
neon/user
neon/password
allfolio/jwt-secret
vercel/allowed-origins
```

Use a JDBC URL for `neon/jdbc-url`:

```text
jdbc:postgresql://HOST/DB?sslmode=require
```

## GitHub Setup

1. Create a 1Password service account with read access to the production vault.
2. Add its token to GitHub:

```text
Repository Settings -> Secrets and variables -> Actions -> New repository secret
Name: OP_SERVICE_ACCOUNT_TOKEN
Value: <1Password service account token>
```

3. Push to `main`, or run **Sync Render Environment** manually from the Actions tab.

Manual runs let you choose whether to trigger a Render deploy after syncing env vars.
Push runs always trigger a Render deploy.

The default workflow syncs only the required backend variables. Add optional
integration keys to both `.env.1password.example` and
`.github/workflows/sync-render-env.yml` when you start using those integrations.

## Local Build

Local build is only a verification step in this setup. You do not upload a local
JAR or Docker image to Render.

Use this before pushing:

```bash
cd allfolio-backend
./gradlew :backend-app:bootJar -x test --no-daemon
```

After pushing to `main`, GitHub Actions loads secrets from 1Password, updates
Render environment variables through the Render API, and asks Render to build
and deploy the latest commit from GitHub.

## Optional Local Secret Loading

If you want to run the backend locally with production-like values, install the
1Password CLI and run a command through `op run`:

```bash
op run --env-file=.env.1password.example -- sh -c 'cd allfolio-backend && ./gradlew :backend-app:bootRun'
```

Do not use production Neon data casually from local development. Prefer a Neon
dev branch or a separate local database when you start testing real user data.

## Render API Behavior

The workflow uses Render's individual environment variable upsert endpoint:

```text
PUT /v1/services/{serviceId}/env-vars/{envVarKey}
```

It then triggers a deploy:

```text
POST /v1/services/{serviceId}/deploys
```

This avoids replacing every existing Render env var at once.
