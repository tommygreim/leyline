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

## Local profiles

The local account service accepts `POST /local/profiles` with JSON
`{"displayName":"Alice"}`. Its response contains `persona_id`, `display_name`,
and `refresh_token`. Each profile receives a server-generated identity; choosing
the same display name does not reuse another player's identity.

Keep the refresh credential private. It persists across server restarts and is
accepted by `/auth/oauth/token` with `grant_type=refresh_token`. To rename the
profile, send `POST /local/profile` with the same JSON body and the refresh
credential as a Bearer token. The persona remains unchanged. Access tokens are
short-lived signed JWTs; they cannot be substituted for refresh credentials.
Password login is disabled by default. Preserve an existing host profile by
provisioning its refresh credential locally before exposing the listeners.

Front Door authenticates each connection with its local access token before
serving player data. Deck lists, writes, deletions, and event selections belong
to the authenticated profile. In two-player mode, the selected decks are copied
when players queue, and both players must select the same event. Direct Bot
Match remains available as a separate practice flow.

## Two-player rooms

The local client menu exposes **Play → Find Match → Play → Timeless Play**.
Both players select their saved decks and enter that queue. This local event
skips server format validation and collection ownership checks; it supplies a
single constructed game with the client's Timeless deck-builder format.

The current set bootstrap advertises `SPM` and `MSH` in that constructed pool.
`OM1`, the retired Through the Omenpaths code, remains only as inactive metadata
for client compatibility and is not a deck-builder set. This distinction matters
for cards whose current primary printing exists only under `SPM` or `MSH`.

Constructed matches use the London mulligan: every mulligan redraw contains
seven cards, and after Keep the player puts one card on the bottom for each
previous mulligan. Card text that offers an optional discard publishes the
discard choice before any dependent target choice; declining the discard closes
the optional branch and returns play to the next normal decision.

Client prompt text comes from numeric localization IDs rather than strings sent
by Forge. Routes with a verified semantic use the matching client prompt (for
example discard-one, discard-two, discard-three, optional discard, and pay X).
Unclassified selections, searches, and optional actions use neutral “choose
items,” “search for a card,” and “choose options” prompts so a fallback does not
claim a restriction or cost that the rules engine did not supply.

When the host enables two-player mode, `Event_EnterPairing` (603) waits for two
separate authenticated profiles selecting the same event. The first queued
profile is seat 1 and the second is seat 2. Their selected decks are copied at
queue time, and both connections receive the same roster with their own seat.
There is one room and one active game per server.

Match Door validates the local access token again and admits only the profile
reserved for that match and seat. A second connection cannot replace an occupied
seat. Human games use two player sessions; the `_Familiar` observer remains
specific to bot matches.

Leaving the queue removes the waiting entry. A reserved room with no Match Door
connections is released when a participant cancels or after five minutes. Once
connected, ending the match or disconnecting releases the room and its deck
snapshots; reconnecting to an interrupted game is not supported. Both players
receive the normal result message when a completed game ends.

## Notes

- Local-only.
- No client binaries are distributed by this repo.
- Restore the client's stock config when finished.
