package com.eventbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.WorldType;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.util.Text;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Slf4j
@PluginDescriptor(
		name = "Event Bridge",
		description = "Sends in-game events as structured JSON to an HTTP endpoint you configure.",
		tags = {"webhook", "api", "events", "discord", "json", "bridge", "loot", "pvp", "clan"}
)
public class EventBridgePlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private EventBridgeConfig config;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private ItemManager itemManager;

	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	/** Plugin version sent with every event. Must match {@code version} in build.gradle. */
	static final String PLUGIN_VERSION = "3.0.0";

	/**
	 * Envelope schema version. Bumped only on a breaking change to the envelope
	 * or to an existing {@code data} shape; new event types or optional fields
	 * do not bump it.
	 */
	static final int SCHEMA_VERSION = 1;

	// ── Event types ───────────────────────────────────────────────────────────

	static final String TYPE_CLAN_CHAT = "CLAN_CHAT";
	static final String TYPE_QUEST_COMPLETED = "QUEST_COMPLETED";
	static final String TYPE_COLLECTION_LOG = "COLLECTION_LOG";
	static final String TYPE_COMBAT_ACHIEVEMENT = "COMBAT_ACHIEVEMENT";
	static final String TYPE_CLUE_COMPLETED = "CLUE_COMPLETED";
	static final String TYPE_ACHIEVEMENT_DIARY = "ACHIEVEMENT_DIARY";
	static final String TYPE_LEVEL_UP = "LEVEL_UP";
	static final String TYPE_XP_MILESTONE = "XP_MILESTONE";
	static final String TYPE_LOOT = "LOOT";
	static final String TYPE_PLAYER_KILL = "PLAYER_KILL";
	static final String TYPE_DEATH = "DEATH";

	// ── Level / XP tracking state ─────────────────────────────────────────────

	/** Last known level per skill — used to detect level-ups. */
	private final Map<String, Integer> lastLevels = new HashMap<>();

	/** Last XP boundary (in units of XP_SEND_THRESHOLD) sent per 99+ skill. */
	private final Map<String, Integer> lastXpBoundary = new HashMap<>();

	/**
	 * How often to report XP for 99+ skills: once per 100k XP gained, so
	 * receivers can track XP goals without an event for every single action.
	 */
	private static final int XP_SEND_THRESHOLD = 100_000;

	/**
	 * On login the client replays StatChanged for every skill, sometimes with
	 * placeholder values first — without a grace period that looks like ~23
	 * simultaneous level-ups. StatChanged events inside the grace window only
	 * update the maps; nothing is sent.
	 */
	private static final int LOGIN_GRACE_TICKS = 3;
	private int ticksSinceLogin = 0;

	// ── Killer attribution ────────────────────────────────────────────────────

	/**
	 * Who killed us: interacting-at-death misses kills where we weren't fighting
	 * back. Instead we remember the last player who actually damaged us (hitsplat
	 * + targeting evidence) and use them when death follows within the timeout.
	 * Players merely targeting/following us never count without damage.
	 */
	private static final int ATTACKER_TIMEOUT_TICKS = 25; // ~15s

	private String lastAttackerName;
	private int lastAttackerTick = -1;
	private String lastTargeterName;

	// Damage we dealt to other players (name → running total + last-hit tick).
	// A player's death within ATTACKER_TIMEOUT_TICKS of our last hit counts as
	// our kill — the reliable signal for loot-key kills, which emit no personal
	// "You have defeated X!" game message. The damage total is sent with the
	// kill so a receiver can credit the top-damage attacker in a multi-way pile.
	private static final class DamageDealt
	{
		int total;
		int tick;
	}
	private final Map<String, DamageDealt> damagedPlayers = new HashMap<>();

	// ── Retry queue for failed POSTs ──────────────────────────────────────────

	private static final int MAX_RETRIES = 3;
	private static final int MAX_RETRY_QUEUE = 50;
	private static final int RETRIES_PER_TICK = 2;

	private static final class PendingPost
	{
		final HttpUrl url;
		final JsonObject payload;
		final int attempt;

		PendingPost(HttpUrl url, JsonObject payload, int attempt)
		{
			this.url = url;
			this.payload = payload;
			this.attempt = attempt;
		}
	}

	private final ConcurrentLinkedDeque<PendingPost> retryQueue = new ConcurrentLinkedDeque<>();

	/** Last invalid endpoint value we warned about, so each bad value is logged once. */
	private String lastInvalidEndpoint;

	/** Set once we warned that the auth token cannot be used as a header value. */
	private boolean warnedInvalidToken;

	// ── Game message patterns ─────────────────────────────────────────────────

	private static final Pattern QUEST_PATTERN = Pattern.compile(
			"(?:Congratulations, you(?:'ve| have) completed a quest|You have completed the quest): (.+?)!?\\s*$",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern CLOG_PATTERN = Pattern.compile(
			"New item added to your collection log: (.+?)\\.",
			Pattern.CASE_INSENSITIVE);

	// Matches e.g. "Congratulations, you've completed a Easy combat task: Snuffed Out."
	private static final Pattern CA_PATTERN = Pattern.compile(
			"Congratulations, you(?:'ve| have) completed (?:a|an) (\\w+) combat (?:task|achievement): (.+?)\\.",
			Pattern.CASE_INSENSITIVE);

	// Matches e.g. "You have completed a Clue Scroll (hard)."
	private static final Pattern CLUE_PATTERN = Pattern.compile(
			"You have completed a [Cc]lue [Ss]croll \\((\\w+)\\)",
			Pattern.CASE_INSENSITIVE);

	// Matches e.g. "Congratulations, you've completed all of the Hard tasks in the Ardougne Diary."
	private static final Pattern DIARY_PATTERN = Pattern.compile(
			"Congratulations, you(?:'ve| have) completed all of the (Easy|Medium|Hard|Elite) tasks? in the (.+?) (?:Diary|diary)",
			Pattern.CASE_INSENSITIVE);

	// ── Config ────────────────────────────────────────────────────────────────

	@Provides
	EventBridgeConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(EventBridgeConfig.class);
	}

	@Override
	protected void shutDown()
	{
		lastLevels.clear();
		lastXpBoundary.clear();
		retryQueue.clear();
		lastAttackerName = null;
		lastAttackerTick = -1;
		lastTargeterName = null;
		damagedPlayers.clear();
		lastInvalidEndpoint = null;
		warnedInvalidToken = false;
	}

	// ── Login/logout state ────────────────────────────────────────────────────

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		switch (event.getGameState())
		{
			case LOGGED_IN:
				// Fresh grace window after every (re)login and world hop
				ticksSinceLogin = 0;
				break;
			case LOGIN_SCREEN:
			case HOPPING:
				// Next login may be a different account — forget seeded levels
				lastLevels.clear();
				lastXpBoundary.clear();
				lastAttackerName = null;
				lastAttackerTick = -1;
				lastTargeterName = null;
				damagedPlayers.clear();
				break;
			default:
				break;
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (ticksSinceLogin < LOGIN_GRACE_TICKS)
		{
			ticksSinceLogin++;
		}
		flushRetryQueue();
	}

	// ── Chat messages ─────────────────────────────────────────────────────────

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		ChatMessageType type = event.getType();

		if (type == ChatMessageType.CLAN_CHAT || type == ChatMessageType.CLAN_MESSAGE)
		{
			handleClanChat(event, type == ChatMessageType.CLAN_MESSAGE);
		}

		// Personal game messages — achievements. PK kills are detected from the
		// victim's death (onActorDeath), not a chat message: loot-key kills
		// emit no "You have defeated X!" line.
		if (type == ChatMessageType.GAMEMESSAGE && config.sendAchievements())
		{
			handleAchievementMessage(event.getMessage());
		}
	}

	private void handleClanChat(ChatMessage event, boolean system)
	{
		if (!config.sendClanChat()) return;

		String message = Text.removeTags(event.getMessage());
		if (message.startsWith("To talk in your clan")) return;

		String sender = event.getName() == null ? "" : Text.removeTags(event.getName());

		JsonObject data = new JsonObject();
		data.addProperty("sender", sender);
		data.addProperty("message", message);
		data.addProperty("system", system);
		send(TYPE_CLAN_CHAT, data);
	}

	private void handleAchievementMessage(String rawMessage)
	{
		String msg = Text.removeTags(rawMessage);
		Matcher m;

		m = QUEST_PATTERN.matcher(msg);
		if (m.find())
		{
			JsonObject data = new JsonObject();
			data.addProperty("quest", m.group(1).trim());
			send(TYPE_QUEST_COMPLETED, data);
			return;
		}

		m = CLOG_PATTERN.matcher(msg);
		if (m.find())
		{
			JsonObject data = new JsonObject();
			data.addProperty("item", m.group(1).trim());
			send(TYPE_COLLECTION_LOG, data);
			return;
		}

		m = CA_PATTERN.matcher(msg);
		if (m.find())
		{
			JsonObject data = new JsonObject();
			data.addProperty("tier", m.group(1).toLowerCase(Locale.ROOT));
			data.addProperty("task", m.group(2).trim());
			send(TYPE_COMBAT_ACHIEVEMENT, data);
			return;
		}

		m = CLUE_PATTERN.matcher(msg);
		if (m.find())
		{
			JsonObject data = new JsonObject();
			data.addProperty("tier", m.group(1).toLowerCase(Locale.ROOT));
			send(TYPE_CLUE_COMPLETED, data);
			return;
		}

		m = DIARY_PATTERN.matcher(msg);
		if (m.find())
		{
			JsonObject data = new JsonObject();
			data.addProperty("tier", m.group(1).toLowerCase(Locale.ROOT));
			data.addProperty("area", m.group(2).trim());
			send(TYPE_ACHIEVEMENT_DIARY, data);
		}
	}

	// ── Stat changes (level-ups + XP milestones for 99+ skills) ──────────────

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		String skillName = event.getSkill().getName();
		int    newLevel  = event.getLevel();
		int    newXp     = event.getXp();

		// Inside the login grace window (and while not fully logged in) events
		// only seed/refresh the maps — no sends. This kills the login burst.
		// The maps are always kept current, so turning the toggle on later
		// does not produce a burst of stale level-ups either.
		boolean suppress = ticksSinceLogin < LOGIN_GRACE_TICKS
				|| client.getGameState() != GameState.LOGGED_IN;

		// Level-up detection — first event per skill just initialises the map
		if (!lastLevels.containsKey(skillName) || suppress)
		{
			lastLevels.put(skillName, newLevel);
		}
		else
		{
			int oldLevel = lastLevels.get(skillName);
			if (newLevel > oldLevel)
			{
				lastLevels.put(skillName, newLevel);

				if (config.sendLevels())
				{
					JsonObject data = new JsonObject();
					data.addProperty("skill", skillName);
					data.addProperty("level", newLevel);
					data.addProperty("xp",    newXp);
					send(TYPE_LEVEL_UP, data);
				}
			}
		}

		// XP milestones for 99+ skills: once per XP_SEND_THRESHOLD gained.
		if (newLevel >= 99)
		{
			int boundary = newXp / XP_SEND_THRESHOLD;

			if (!lastXpBoundary.containsKey(skillName) || suppress)
			{
				lastXpBoundary.put(skillName, boundary);
			}
			else if (boundary > lastXpBoundary.get(skillName))
			{
				lastXpBoundary.put(skillName, boundary);

				if (config.sendLevels())
				{
					JsonObject data = new JsonObject();
					data.addProperty("skill", skillName);
					data.addProperty("xp",    newXp);
					send(TYPE_XP_MILESTONE, data);
				}
			}
		}
	}

	// ── Loot ──────────────────────────────────────────────────────────────────

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived event)
	{
		JsonObject source = new JsonObject();
		source.addProperty("type", "NPC");
		NPC npc = event.getNpc();
		if (npc != null)
		{
			if (npc.getName() != null)
			{
				source.addProperty("name", Text.removeTags(npc.getName()));
			}
			source.addProperty("id", npc.getId());
		}
		sendLoot(source, event.getItems());
	}

	@Subscribe
	public void onPlayerLootReceived(PlayerLootReceived event)
	{
		JsonObject source = new JsonObject();
		source.addProperty("type", "PLAYER");
		Player victim = event.getPlayer();
		if (victim != null && victim.getName() != null)
		{
			String name = Text.removeTags(victim.getName());
			if (!name.isEmpty())
			{
				source.addProperty("name", name);
			}
		}
		sendLoot(source, event.getItems());
	}

	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		// Wilderness loot keys are opened at a chest outside the wilderness;
		// the loot tracker reports the contents under the name "Loot Chest".
		String name = event.getName();
		if (name == null || !name.equalsIgnoreCase("Loot Chest")) return;

		JsonObject source = new JsonObject();
		source.addProperty("type", "LOOT_KEY");
		sendLoot(source, event.getItems());
	}

	/** One LOOT event per loot drop, with every item in it. */
	private void sendLoot(JsonObject source, Collection<ItemStack> items)
	{
		if (!config.sendLoot() || resolveEndpoint() == null) return;

		JsonArray itemsArray = new JsonArray();
		long totalValue = 0;

		for (ItemStack item : items)
		{
			int    itemId   = item.getId();
			int    quantity = item.getQuantity();
			String itemName = itemManager.getItemComposition(itemId).getName();
			long   gePrice  = itemManager.getItemPrice(itemId);

			JsonObject entry = new JsonObject();
			entry.addProperty("id",       itemId);
			entry.addProperty("name",     itemName);
			entry.addProperty("quantity", quantity);
			entry.addProperty("gePrice",  gePrice);
			itemsArray.add(entry);

			totalValue += gePrice * quantity;
		}

		if (itemsArray.size() == 0) return;
		if (totalValue < config.minLootValue()) return;

		JsonObject data = new JsonObject();
		data.add("source", source);
		data.add("items", itemsArray);
		data.addProperty("totalValue", totalValue);
		data.addProperty("pvpArea", isPvpArea());
		send(TYPE_LOOT, data);
	}

	// ── Kills and deaths ──────────────────────────────────────────────────────

	@Subscribe
	public void onInteractingChanged(InteractingChanged event)
	{
		// Identity hint only — no timestamp. Someone targeting us becomes the
		// attacker candidate, but only a hitsplat promotes them to attacker.
		if (event.getSource() instanceof Player
				&& event.getSource() != client.getLocalPlayer()
				&& event.getTarget() == client.getLocalPlayer())
		{
			lastTargeterName = Text.removeTags(event.getSource().getName());
		}
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		Actor actor = event.getActor();

		// Damage we dealt to another player — kill-attribution candidate.
		if (actor instanceof Player && actor != client.getLocalPlayer()
				&& event.getHitsplat().isMine() && actor.getName() != null)
		{
			String name = Text.removeTags(actor.getName());
			int tick = client.getTickCount();
			DamageDealt d = damagedPlayers.get(name);
			// Reset the running total when the previous hit is stale, so damage
			// is scoped to the current engagement rather than a past one.
			if (d == null || tick - d.tick > ATTACKER_TIMEOUT_TICKS)
			{
				d = new DamageDealt();
				damagedPlayers.put(name, d);
			}
			d.total += event.getHitsplat().getAmount();
			d.tick = tick;
		}

		if (actor != client.getLocalPlayer()) return;

		// Damage landed on us — attribute it to whoever is targeting us right
		// now, falling back to the last player seen targeting us.
		String attacker = null;
		for (Player p : client.getPlayers())
		{
			if (p != client.getLocalPlayer() && p.getInteracting() == client.getLocalPlayer())
			{
				attacker = Text.removeTags(p.getName());
				break;
			}
		}
		if (attacker == null)
		{
			attacker = lastTargeterName;
		}

		if (attacker != null)
		{
			lastAttackerName = attacker;
			lastAttackerTick = client.getTickCount();
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		Actor actor = event.getActor();

		// Another player died — our kill if we damaged them recently.
		if (actor instanceof Player && actor != client.getLocalPlayer())
		{
			handlePotentialKill((Player) actor);
			return;
		}

		if (actor != client.getLocalPlayer()) return;
		if (!config.sendPvp()) return;

		// Recent damage-dealer first; fallback: whoever we were interacting with
		String killer = null;
		if (lastAttackerName != null
				&& client.getTickCount() - lastAttackerTick <= ATTACKER_TIMEOUT_TICKS)
		{
			killer = lastAttackerName;
		}
		if (killer == null)
		{
			Actor interacting = client.getLocalPlayer().getInteracting();
			if (interacting instanceof Player)
			{
				killer = Text.removeTags(interacting.getName());
			}
		}

		JsonObject data = new JsonObject();
		if (killer != null && !killer.isEmpty())
		{
			data.addProperty("killer", killer);
		}
		data.addProperty("pvpArea", isPvpArea());
		send(TYPE_DEATH, data);
	}

	/**
	 * A nearby player died — attribute it to us only if we dealt them damage
	 * within ATTACKER_TIMEOUT_TICKS. Works for loot-key kills (no "defeated"
	 * message); the location is the victim's tile, i.e. the actual kill spot.
	 */
	private void handlePotentialKill(Player victim)
	{
		if (victim.getName() == null) return;

		String victimName = Text.removeTags(victim.getName());
		DamageDealt dealt = damagedPlayers.remove(victimName);
		if (dealt == null || client.getTickCount() - dealt.tick > ATTACKER_TIMEOUT_TICKS)
		{
			return; // we did not (recently) damage them — not our kill
		}

		if (!config.sendPvp()) return;

		JsonObject data = new JsonObject();
		data.addProperty("victim",  victimName);
		data.addProperty("damage",  dealt.total);
		data.addProperty("pvpArea", isPvpArea());
		send(TYPE_PLAYER_KILL, data, victim);
	}

	// ── Location helpers ──────────────────────────────────────────────────────

	private static final WorldArea WILDERNESS_ABOVE_GROUND = new WorldArea(2944, 3523, 448, 448, 0);
	private static final WorldArea WILDERNESS_UNDERGROUND  = new WorldArea(2944, 9918, 320, 442, 0);

	private boolean isInWilderness()
	{
		if (client.getLocalPlayer() == null)
		{
			return false;
		}
		WorldPoint loc = client.getLocalPlayer().getWorldLocation();
		return loc.isInArea2D(WILDERNESS_ABOVE_GROUND, WILDERNESS_UNDERGROUND);
	}

	private boolean isPvpWorld()
	{
		EnumSet<WorldType> types = client.getWorldType();
		return types.contains(WorldType.PVP) || types.contains(WorldType.DEADMAN);
	}

	/** True in the Wilderness or anywhere on a PvP/Deadman world. */
	private boolean isPvpArea()
	{
		return isInWilderness() || isPvpWorld();
	}

	// ── Envelope ──────────────────────────────────────────────────────────────

	/** Sends an event located at the local player's position. */
	private void send(String type, JsonObject data)
	{
		send(type, data, client.getLocalPlayer());
	}

	/**
	 * Wraps {@code data} in the envelope and posts it. Does nothing while no
	 * valid endpoint is configured or the local player's name is unknown.
	 *
	 * @param locationActor actor whose position goes in {@code location}
	 */
	private void send(String type, JsonObject data, Actor locationActor)
	{
		HttpUrl url = resolveEndpoint();
		if (url == null) return;

		String playerName = getLocalPlayerName();
		if (playerName == null) return;

		JsonObject player = new JsonObject();
		player.addProperty("name", playerName);
		player.addProperty("world", client.getWorld());

		JsonObject envelope = new JsonObject();
		envelope.addProperty("schema", SCHEMA_VERSION);
		envelope.addProperty("pluginVersion", PLUGIN_VERSION);
		envelope.addProperty("type", type);
		envelope.addProperty("timestamp", System.currentTimeMillis());
		envelope.add("player", player);

		WorldPoint loc = locationActor != null ? locationActor.getWorldLocation() : null;
		if (loc != null)
		{
			JsonObject location = new JsonObject();
			location.addProperty("x",     loc.getX());
			location.addProperty("y",     loc.getY());
			location.addProperty("plane", loc.getPlane());
			envelope.add("location", location);
		}

		envelope.add("data", data);

		postJson(url, envelope, 0);
	}

	private String getLocalPlayerName()
	{
		if (client.getLocalPlayer() == null || client.getLocalPlayer().getName() == null)
		{
			return null;
		}
		String name = Text.removeTags(client.getLocalPlayer().getName());
		return name.isEmpty() ? null : name;
	}

	// ── Transport ─────────────────────────────────────────────────────────────

	/**
	 * The configured endpoint, or null when it is empty or not a valid
	 * http(s) URL. An invalid value is logged once; this never throws.
	 */
	private HttpUrl resolveEndpoint()
	{
		String raw = config.endpointUrl();
		if (raw == null || raw.trim().isEmpty())
		{
			return null;
		}
		raw = raw.trim();

		HttpUrl url = HttpUrl.parse(raw);
		if (url == null || !(url.scheme().equals("http") || url.scheme().equals("https")))
		{
			if (!raw.equals(lastInvalidEndpoint))
			{
				lastInvalidEndpoint = raw;
				log.warn("Event Bridge: the endpoint URL is not a valid http(s) URL; no events are sent");
			}
			return null;
		}
		return url;
	}

	private void postJson(HttpUrl url, JsonObject payload, int attempt)
	{
		Request request;
		try
		{
			Request.Builder builder = new Request.Builder()
					.url(url)
					.post(RequestBody.create(JSON, payload.toString()));

			String token = config.authToken();
			if (token != null && !token.trim().isEmpty())
			{
				builder.header("Authorization", "Bearer " + token.trim());
			}
			request = builder.build();
		}
		catch (IllegalArgumentException e)
		{
			// OkHttp rejects header values with control or non-ASCII characters.
			if (!warnedInvalidToken)
			{
				warnedInvalidToken = true;
				log.warn("Event Bridge: the auth token contains characters that cannot be sent in a header; no events are sent");
			}
			return;
		}

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				// Network failure only (non-2xx responses land in onResponse and
				// are never retried). Requeue; the game-tick drain resends at most
				// RETRIES_PER_TICK per tick, with no growing delay between attempts.
				if (attempt < MAX_RETRIES && retryQueue.size() < MAX_RETRY_QUEUE)
				{
					retryQueue.add(new PendingPost(url, payload, attempt + 1));
				}
				else
				{
					log.warn("Event Bridge: failed to POST to {} (giving up): {}", url.host(), e.getMessage());
				}
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				if (!response.isSuccessful())
				{
					log.debug("Event Bridge: {} responded {} to a {} event",
							url.host(), response.code(), payload.get("type").getAsString());
				}
				response.close();
			}
		});
	}

	/** Resends a couple of failed POSTs per game tick (~0.6s spacing). */
	private void flushRetryQueue()
	{
		for (int i = 0; i < RETRIES_PER_TICK; i++)
		{
			PendingPost pending = retryQueue.poll();
			if (pending == null)
			{
				return;
			}
			postJson(pending.url, pending.payload, pending.attempt);
		}
	}
}
