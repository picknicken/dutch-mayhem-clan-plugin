# Tekst voor de pull request bij runelite/plugin-hub

Aanpassing in het bestand `plugins/dutch-mayhem-clan-plugin` (in jouw fork van runelite/plugin-hub):

```
repository=https://github.com/picknicken/dutch-mayhem-clan-plugin.git
commit=bffdf5c88fab8cd38ef824685e3b13efa20c0663
```

Haal de regels `disabled=unmaintained` en `warning=...` weg. Vul de commit in van de laatste versie die je echt wilt
uitbrengen (zie "Voor je indient" in je eigen notities). De hash hierboven is de bouwfix op de werkbranch.

## Titel

`update dutch-mayhem-clan-plugin: fix build, re-enable, move to picknicken/`

## Beschrijving (Engels, zoals RuneLite verwacht)

> **Re-enabling Dutch Mayhem Clan plugin (fixes build, repository moved)**
>
> This plugin is currently marked `disabled=unmaintained`. It is maintained: it is the companion plugin for the
> Dutch Mayhem Clan (DMC), used by our clan members every week.
>
> **What changed**
> - **Build fixed.** `ItemManager.getItemPrice()` now returns `long`; the previously pinned commit used `int` and no
>   longer compiled. The fix is two lines (`int gePrice` -> `long gePrice` in `DiscordCCPlugin.java`).
> - **Repository moved** from `nekomana-project/dutch-mayhem-clan-plugin` to `picknicken/dutch-mayhem-clan-plugin`.
>   The new repository is a continuation of the old one (same history up to the pinned commit) and is the only one
>   that will be maintained. Maintainer: @picknicken.
> - Removed `disabled=unmaintained` and the old `warning=` line.
>
> **What the plugin does**
> - Mirrors the DMC clan chat to the clan's own Discord server.
> - Tracks PvP loot in the Wilderness and on PvP/Deadman worlds for the clan's loot-split sessions.
> - Reports PvP kills and deaths (with map position) so the clan website can show them.
> - Reports progress events during clan bingo (drops, level-ups, quests, collection log, combat achievements,
>   clue scrolls, diaries).
>
> **Data and privacy**
> - **Nothing is sent until the user fills in a Server URL and a personal token.** Both are empty by default.
> - Data only goes to that user-configured server (the clan's own bot), with the user's personal token. No other
>   server is contacted and there are no analytics.
> - What is sent, only when the event happens: clan chat lines from the DMC clan chat; the user's RSN with loot,
>   kills/deaths and bingo events. Loot and PvP data are only sent in the Wilderness or on PvP/Deadman worlds.
> - The token field is a RuneLite `secret` config item.
> - The README documents all of this ("What data is sent, and where").
>
> **Rules**
> - The plugin only reads game state and sends it out. It does not click, move, automate or change anything in the
>   game, and it does not give any gameplay advantage.
> - It does not show other players' hidden information. Kill/death reports use the names of the players involved in
>   the kill that the user themself sees in game.
> - No third-party dependencies beyond what `runelite-client` provides (`build=standard`).
>
> Happy to change or remove anything the reviewers are not comfortable with, for example limiting what is sent
> for PvP events.

## Voor je indient (checklist)

1. De bouwfix zit in de commit die je indient. Ik kon hem zelf niet bouwen, want `repo.runelite.net` is hier niet
   bereikbaar. Draai de `run`-taak in IntelliJ, of kijk of de bouwcontrole in de pull request groen is.
2. Kies de commit die je wilt indienen (volledige 40 tekens). Hierboven staat de bouwfix. Is `main` later
   bijgewerkt, gebruik dan die nieuwere commit.
3. De commit moet in een openbare repo staan en bereikbaar zijn, ook als hij nog niet op `main` staat.
4. Controleer `runelite-plugin.properties`: `author=` staat nog op `nekomana-project`. Zet daar de naam neer die in
   de lijst moet staan.
5. Vul `@picknicken` in de tekst aan met je eigen GitHub-naam als dat anders is.
6. De beschrijving is mijn voorstel. Lees hem door en controleer vooral de zinnen onder "Rules". Die moeten kloppen
   met wat de plugin echt doet.
