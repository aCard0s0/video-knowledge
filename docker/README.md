# Docker build assets

Shared build-time assets used by the application Dockerfiles (build context = repo root).

| File | Used by | Purpose |
|---|---|---|
| `scripts/install-maven.sh` | vidingest-* Dockerfiles | Install a pinned Maven into the JDK builder stage |
| `scripts/create-app-user.sh` | vidingest-* Dockerfiles | Create the non-root runtime user |
| `settings.xml` | vidingest-* Dockerfiles | Maven settings enabling Spring milestone/snapshot repos |

These are referenced by the `vidingest-server`, `vidingest-cli`, and `vidingest-mcp`
Dockerfiles via `COPY docker/scripts/... ` and `COPY docker/settings.xml`.

To build and run the stack, use [`scripts/tradey.sh`](../scripts/tradey.sh) (see the
[root README](../README.md)), which layers `compose.yml` + `compose/*`.
