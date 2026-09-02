# Tailnet ACL for this stack

Write this **before** the first `./vk start --serve https`, not after. The ACL decides which
machines may connect, and its port must match `TS_SERVE_MODE` — a mismatch denies access with
a plain timeout and no log line on either side saying "ACL". The failure reads as a broken
deployment rather than a policy decision, which is why the reflex is to widen the ACL until
something works. Check the pair every time either half changes.

| `TS_SERVE_MODE` | ACL `dst` | also needs |
|---|---|---|
| `https` (default) | `tag:server:443` | — |
| `funnel` | `tag:server:443` | a `nodeAttrs` funnel grant, below |

Paste into the admin console → **Access Controls**, replacing the address:

```jsonc
{
  "tagOwners": {
    "tag:server": ["you@example.com"]
  },
  "acls": [
    {
      "action": "accept",
      "src": ["you@example.com"],
      "dst": ["tag:server:443"]
    }
  ]
}
```

Then mint the key at **Settings → Keys**: **reusable** and **tagged** `tag:server`. Tagged
nodes never expire, which is the whole reason to prefer them — an untagged node silently falls
off the tailnet after 90 days. Do **not** use an ephemeral key: ephemeral nodes are deleted on
disconnect, which fights the `tailscale_state` volume and burns a new node on every restart.

Put it in `.env` as `TS_AUTHKEY`. It is spent once — identity lives in the volume and
`TS_AUTH_ONCE=true` stops re-authentication — so rotating it needs no redeploy.

## Before you enable HTTPS at all

Admin console → **DNS** → **HTTPS Certificates**. There is no plaintext mode, and this is not
optional: Serve derives `${TS_CERT_DOMAIN}` from the node's cert domain, and it is the only
substitution containerboot performs. With certificates off there is nothing to substitute, so
the `Web` handler key matches no incoming Host and **the node comes up healthy while serving
nothing** — the worst shape a misconfiguration can take.

## Funnel is not appropriate for this stack

`TS_SERVE_MODE=funnel` publishes the console to the public internet. Beyond the node attribute
it needs:

```jsonc
"nodeAttrs": [
  { "target": ["tag:server"], "attr": ["funnel"] }
]
```

Do not. **This application has no authentication of any kind** — no Spring Security, no login,
nothing. The tailnet ACL is the entire access control, and funnel is precisely the setting that
turns that ACL into decoration. Anyone with the URL would reach `POST /api/v1/pipelines` (which
spawns `yt-dlp` and `ffmpeg` on arbitrary input), `DELETE /api/v1/videos/{id}` (a recursive
directory delete), and `PUT /api/v1/connections/{name}`.

That last one is the sharpest edge and it is worth stating plainly: a connection's `base_url`
is operator-editable and `POST /connections/{name}/test` sends the **stored API key** to
whatever base URL is configured. So anyone who can reach the connections API can repoint a
connection at a host they control and have the server hand over the OpenAI key. That is true on
the tailnet too — it is the reason the ACL `src` should be your own account and not
`autogroup:members`.

`./vk start --serve funnel` warns loudly and requires `--yes`, and the file exists so the
posture is one variable rather than a compose edit. It is not a recommendation.

## Identity is available and currently unused

Serve injects `Tailscale-User-Login`, `Tailscale-User-Name` and `Tailscale-User-ProfilePic` on
every proxied request, and **strips any inbound header of the same name**, so the value cannot
be spoofed. Nothing in this repo reads them yet, so there is no per-user authorization and no
audit trail — every request is an anonymous operator.

Two things to know before wiring it up:

- The headers are **absent** for funnel traffic *and* for tagged devices. Requiring a valid
  login therefore authenticates tailnet users and rejects the public internet with one rule —
  which is what would make funnel survivable — but it also locks out any `tag:ci` runner.
- Presence is not membership: external users who accepted a share have headers set too.
  Authorize on the value, never on the header merely existing.

Any such filter depends on the app being unreachable except through Serve. `--local`, kernel
mode, or publishing a debug port all bypass it.

## Teardown

`./vk down` runs this for you when the sidecar is up. Order matters:

```bash
docker compose exec tailscale tailscale logout
```

`logout` invalidates the node's key and removes it from the tailnet, and it has to happen
while the container can still reach the coordination server. Skip it and the machine entry
survives holding the MagicDNS name, so the next deploy authenticates fresh and comes up as
`video-knowledge-1` — while every URL, bookmark and ACL still points at the corpse. If that
already happened, delete the machine at
[login.tailscale.com/admin/machines](https://login.tailscale.com/admin/machines).

## Verify, rather than trusting this file

```bash
./vk status --serve https
```

reports the node's observed `BackendState` and real `DNSName`, not what `.env` says. Then, and
the audit cannot do either of these for you:

- from another tailnet device, `curl -sSf https://<name>.<tailnet>.ts.net/vidingest/` succeeds
- from a machine **off** the tailnet, the same URL must fail to connect. If it answers,
  something is published that you did not intend — most likely a host port, or funnel left on.
