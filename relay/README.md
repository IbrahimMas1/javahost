# JavaHost relay

When two friends can't connect straight to each other (some routers and mobile networks block it), their games meet
at a relay instead. e4mc's free relays work everywhere but are in Germany, the US and Singapore; a JavaHost relay
close to the players cuts the ping a lot. This is that relay: one small Java program.

It passes datagrams between two players and nothing else. It can't read the game's traffic (Minecraft encrypts it),
it keeps no record of players, and it only talks to addresses that proved they asked.

## What you need

- A server with a public IP address near your players, running Linux. The smallest size any provider sells is
  plenty (1 CPU, 512 MB of memory). What matters is where it is and how much data it may send each month:
  as a very rough guide, count on about 100 MB an hour for each player connected through it (more while they explore new terrain).
- One UDP port open to the internet (25580 below).

## Set it up

On the server:

```bash
sudo apt update && sudo apt install -y openjdk-21-jre-headless curl
```

```bash
curl -fsSLO https://raw.githubusercontent.com/IbrahimMas1/javahost/main/src/main/java/com/javahost/util/RelayServer.java
```

```bash
java RelayServer.java 25580
```

It prints `JavaHost relay listening on UDP port 25580`. Now open that port:

- In your provider's firewall (often called a security group or security list): allow **UDP 25580** from anywhere.
- On the server itself, if it runs a firewall: `sudo ufw allow 25580/udp`

### Keep it running

To start it with the server and restart it if it stops, save this as `/etc/systemd/system/javahost-relay.service`
(change the path if `RelayServer.java` isn't in `/opt/javahost`):

```ini
[Unit]
Description=JavaHost relay
After=network-online.target
Wants=network-online.target

[Service]
ExecStart=/usr/bin/java -Xmx128m /opt/javahost/RelayServer.java 25580
Restart=always
RestartSec=5
DynamicUser=yes
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl enable --now javahost-relay
```

```bash
journalctl -u javahost-relay -f
```

Every ten minutes the log says how many pairs of players are connected and how much data went through. It never
logs who.

## Tell JavaHost about it

**For everyone** (official relays): add it to [`website/relays.json`](../website/relays.json) and push. Games read
that list when they start and a few times a day, so a new relay is in use within hours and no mod update is needed.

```json
{
	"relays": [
		{ "name": "UAE", "address": "relay-uae.example.com:25580" }
	]
}
```

The name is shown to players ("Connected through the UAE relay"). The address can be a name or an IP address.

**Just for you and your friends:** add it to `config/javahost.json` instead. Everyone who should use it needs the
same line:

```json
"relays": ["203.0.113.10:25580"]
```

Either way, both players must know a relay for it to be used; the two games measure their ping to each one and pick
the relay with the lowest total.

## Limits it enforces

- Two players per room; rooms close after a minute of silence.
- Each room may send 8 MB at once, then 400 KB a second (a join's burst of chunks, then normal play).
- At most 4000 rooms, and 40 per address.

To change them, edit the constants at the top of `RelayServer.java`.
