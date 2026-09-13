---
summary: "Minimal local setup for end-to-end playtesting."
read_when:
  - "running end-to-end local client playtests"
  - "configuring the local client to connect to leyline"
  - "setting up localhost TLS for client-compatible runs"
---
# Local Setup

Needed for end-to-end local playtesting only.

## Requirements

- Compatible client installed separately
- Local connection config installed in the client
- Trusted localhost TLS cert
- Leyline started with the matching cert/key

## Steps

1. Install the local connection config.

   Install `app/main/resources/services.conf` as the client `StreamingAssets/services.conf`.

2. Create and trust a localhost TLS cert.

   Provide your own trusted cert/key for `localhost`. By default, Leyline reads
   `server-chain.pem` and `server.key` from
   `~/Library/Application Support/dev.leyline/tls/`. Set `LEYLINE_CERTS` to use
   another directory.

3. Start Leyline.

   Run `just serve`.

   Pass `--cert` / `--key` or set `LEYLINE_CERT_PATH` / `LEYLINE_KEY_PATH` to
   override the default pair.

## Linux with Steam and Proton

Run Leyline natively on Linux and continue launching the Windows client through
Steam with Proton. Recompiling the client is not required.

Set `LEYLINE_ARENA_DOWNLOADS` when starting Leyline to select the client's
downloaded assets, including both the card database and cached manifests:

```bash
export LEYLINE_ARENA_DOWNLOADS="$HOME/.local/share/Steam/steamapps/common/MTGA/MTGA_Data/Downloads"
just serve
```

Change the path for another Steam library. Without this override, discovery
checks `~/.local/share/Steam`, `~/.steam/steam`, `~/.steam/root`, and the Flatpak
Steam library at `~/.var/app/com.valvesoftware.Steam/.local/share/Steam` for
`steamapps/common/MTGA/MTGA_Data/Downloads`.

An invalid `LEYLINE_ARENA_DOWNLOADS` directory fails explicitly.
`LEYLINE_CARD_DB` still takes precedence for the card database only; it does
not select the manifests. An invalid `LEYLINE_CARD_DB` never falls back to
another database.

The local connection config belongs at
`MTGA_Data/StreamingAssets/services.conf` inside the same Steam installation.
The client must trust the localhost certificate inside its Proton prefix;
adding it to the Linux host's trust store alone is insufficient.

## Clients with typed startup messages

Newer clients request `StartHookResponseV2` as a protobuf `Any`. For these clients,
set `LEYLINE_CLIENT_SCHEMA` to a local `FileDescriptorSet` extracted from the
installed client's protobuf assembly. Leyline uses those descriptors to encode
its local startup state. Keep the extracted schema outside the source checkout;
it is specific to the installed client version and is not distributed here.

Legacy JSON startup requests continue to use JSON responses. A typed startup
request without a configured schema fails with an explicit server-side error.

## Deck persistence

Current client deck editing uses JSON requests independently of typed startup:
`Deck_UpsertDeckV3` (412) saves a deck, `Deck_GetDeckSummariesV3` (411) refreshes
the player's summaries, and `Deck_GetDeck` (400) reloads its card lists. The
summary response contains `Summaries`; the body response contains the deck's
card lists directly. The server serves only decks belonging to the local player.

## Bot match results

Direct Bot Match games are practice matches: they start without joining an
event course, and winning or conceding does not change course wins or losses.
Joined events retain their normal course progression and prize thresholds.

## Notes

- Local-only.
- No client binaries are distributed by this repo.
- Restore the client's stock config when finished.
