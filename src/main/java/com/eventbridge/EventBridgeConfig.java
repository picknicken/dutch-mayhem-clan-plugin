package com.eventbridge;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup(EventBridgeConfig.GROUP)
public interface EventBridgeConfig extends Config
{
	String GROUP = "eventbridge";

	@ConfigSection(
			name = "Connection",
			description = "Where events are sent",
			position = 0
	)
	String connectionSection = "connection";

	@ConfigSection(
			name = "Events",
			description = "Which events are sent",
			position = 1
	)
	String eventsSection = "events";

	// ── Connection ────────────────────────────────────────────────────────────

	@ConfigItem(
			keyName = "endpointUrl",
			name = "Endpoint URL",
			description = "Full http(s) URL that receives a POST for every event. Nothing is sent while this is empty.",
			position = 0,
			section = connectionSection
	)
	default String endpointUrl()
	{
		return "";
	}

	@ConfigItem(
			keyName = "authToken",
			name = "Auth token",
			description = "Optional. Sent as 'Authorization: Bearer <token>' with every request.",
			position = 1,
			section = connectionSection,
			secret = true
	)
	default String authToken()
	{
		return "";
	}

	// ── Events ────────────────────────────────────────────────────────────────

	@ConfigItem(
			keyName = "sendClanChat",
			name = "Clan chat",
			description = "Send clan chat messages, including what other clan members say (CLAN_CHAT).",
			position = 0,
			section = eventsSection
	)
	default boolean sendClanChat()
	{
		return false;
	}

	@ConfigItem(
			keyName = "sendAchievements",
			name = "Achievements",
			description = "Send quests, collection log entries, combat achievements, clue scrolls and achievement diaries.",
			position = 1,
			section = eventsSection
	)
	default boolean sendAchievements()
	{
		return true;
	}

	@ConfigItem(
			keyName = "sendLevels",
			name = "Levels and XP",
			description = "Send level-ups and XP milestones (every 100k XP in level 99 skills).",
			position = 2,
			section = eventsSection
	)
	default boolean sendLevels()
	{
		return true;
	}

	@ConfigItem(
			keyName = "sendLoot",
			name = "Loot",
			description = "Send loot from NPCs, players and loot keys (LOOT).",
			position = 3,
			section = eventsSection
	)
	default boolean sendLoot()
	{
		return true;
	}

	@Range(min = 0)
	@ConfigItem(
			keyName = "minLootValue",
			name = "Minimum loot value",
			description = "Skip loot events whose total GE value is below this amount.",
			position = 4,
			section = eventsSection
	)
	default int minLootValue()
	{
		return 0;
	}

	@ConfigItem(
			keyName = "sendPvp",
			name = "PvP kills and deaths",
			description = "Send player kills and your own deaths (PLAYER_KILL, DEATH).",
			position = 5,
			section = eventsSection
	)
	default boolean sendPvp()
	{
		return true;
	}
}
