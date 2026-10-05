# Deploying the backend

Everything deploys through GitHub Actions. There are no manual steps in the
Render or DigitalOcean consoles for a normal release.

| Environment | Runs on | URL variable | Deployed by |
|---|---|---|---|
| staging | Render (image-backed web service) | `STAGING_API_URL` | every merge to `main`; any PR labelled `deploy:staging` |
| production | DigitalOcean App Platform | `PROD_API_URL` | every merge to `main` after staging is verified; `promote-to-production` (approval) |

The artifact is always the Docker image `myntrack/inventory-backend:<full commit sha>`
built once by `_build-image.yml` and pushed to Docker Hub. The `latest` tag is not used.

## The normal release

1. A pull request is merged to `main` (branch protection: PR + green CI).
2. `Release` workflow: tests → build image `:sha` → deploy to staging → verify →
   deploy to production → verify. No approval step; merging is the decision to ship.
3. "Verify" means the pipeline reads `GET /actuator/health` (`UP`) and
   `GET /commit.txt` (must equal the sha) from the live service, then checks health
   three more times. If staging fails verification, production is not touched.
4. Each deploy writes a summary (sha, previous sha, platform link). Open the run on
   the Actions tab, or the **Environments** page of the repo for "what is where".

Backend and frontend release independently. When a change needs both, merge the
backend PR first, wait for its release run to go green, then merge the frontend PR
(the frontend depends on the API contract and on `CLIENT_URL` CORS settings).

## Trying a branch on staging

Add the label `deploy:staging` to the pull request. The PR's head commit is built
and deployed to staging; a comment on the PR shows the sha and links, and is
updated on every push while the label stays on. Remove the label to stop.

Staging is shared and **newest wins**: a newer push cancels an older staging deploy
job and the in-progress Render deploy, then deploys itself (Render keeps the old
instance serving until the new one is healthy, so nothing goes down). The
superseded run shows as cancelled. The next merge to `main` takes staging back.
Pull requests from forks cannot be deployed (fork workflows get no secrets).

Branch deploys never reach production.

## Promoting a pull request to production (hotfix path)

Once the PR is on staging and you have tested it, add the label **`deploy:production`**.
The **PR → production** workflow:

1. checks the PR's image exists and that staging is *currently serving that exact
   commit* (so staging cannot be skipped);
2. waits for a reviewer of the `production-manual` environment to approve
   ("Review deployments" on the run page);
3. deploys and verifies, then removes the label and comments on the PR.

The reason recorded on the run is `PR #N promoted by @user: <PR title>`. Adding the
label again after new pushes promotes the newer commit (after it has been on staging).

Production is then running an unmerged commit: merge the PR soon, because any other
merge to `main` would replace it.

## Putting an arbitrary build in production (rollback, re-deploy)

Actions → **promote-to-production** → Run workflow, with:

- `sha`: the full 40-character commit hash. Its image must already exist
  (it was deployed to staging via label, or merged to `main` earlier).
- `reason`: free text, shown to the approver and kept in the run.

The workflow checks the image exists, then waits for a reviewer of the
`production-manual` environment to approve. After approval it deploys and verifies.

## Rolling back

Open the last successful production run; its summary ends with
`Rollback: run promote-to-production with sha=<previous>`. Run promote with that
sha and a reason such as `rollback: <what broke>`. Approve. Done.

## Running the deploy scripts by hand

The workflows only call scripts in `scripts/deploy/`; the same scripts work from a
laptop with the same variables:

```bash
export RENDER_API_KEY=… RENDER_SERVICE_ID=…
scripts/deploy/render-deploy.sh <sha>
scripts/deploy/verify-backend.sh https://<staging-host> <sha> 10

export DIGITALOCEAN_TOKEN=… DO_APP_ID=…
scripts/deploy/do-deploy.sh <sha>
scripts/deploy/verify-backend.sh https://<prod-host> <sha> 15
```

See `.do/README.md` for how the DigitalOcean app spec is handled and the
production environment variables.

## GitHub configuration this relies on

Environments (Settings → Environments):

| Environment | Reviewers | Deployment branches | Secrets | Variables |
|---|---|---|---|---|
| `staging` | none | any | `RENDER_API_KEY` | `RENDER_SERVICE_ID`, `STAGING_API_URL` |
| `production` | none | `main` only | `DIGITALOCEAN_TOKEN` | `DO_APP_ID` (bare UUID, no `?i=…`), `PROD_API_URL` |
| `production-manual` | team `stockkart-release-approvers` | any | `DIGITALOCEAN_TOKEN` | `DO_APP_ID` (bare UUID, no `?i=…`), `PROD_API_URL` |

Repository secrets: `DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN`.
Optional repository variable: `IMAGE_REPOSITORY` (default `docker.io/myntrack/inventory-backend`).
Labels: `deploy:staging`, `deploy:production`.

`production` and `production-manual` point at the same real production; two
environments exist only because GitHub attaches reviewers per environment. The
`main`-only branch rule on `production` is what keeps a branch sha from skipping
the approval.

Secret scanning and push protection are enabled on the repository, and
`CI hygiene` fails any PR that tracks a `.env` file.

## Workflows

| File | Trigger | Purpose |
|---|---|---|
| `release.yml` | push to `main` | test → build → staging → production |
| `branch-staging.yml` | PR labelled `deploy:staging` / pushes while labelled | build PR head → staging → PR comment |
| `branch-promote.yml` | PR labelled `deploy:production` | PR head (already on staging) → production, with approval; label removed afterwards |
| `promote.yml` | manual | any built sha → production, with approval (rollbacks) |
| `build-checker.yml` | PR | tests |
| `ci-hygiene.yml` | PR, push to `main` | actionlint, shellcheck, no `.env` |
| `_build-image.yml`, `_deploy-staging.yml`, `_deploy-production.yml` | called by the above | shared jobs so the three paths cannot drift |
