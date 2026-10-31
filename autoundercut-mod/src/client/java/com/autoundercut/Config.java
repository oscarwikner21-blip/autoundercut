package com.autoundercut;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

/** Einstellungen, gespeichert in config/autoundercut.json */
public final class Config {
	/** true = nur anzeigen, was passieren wuerde (nichts wird storniert/neu eingestellt). */
	public boolean dryRun = true;
	/** Pause zwischen zwei Pruefdurchgaengen. */
	public int checkIntervalSeconds = 10;
	/** Um wie viel der guenstigste Konkurrent unterboten wird. */
	public long undercut = 1;
	/** Wie viele Stacks pro Durchgang hoechstens neu eingestellt werden. */
	public int maxRelistsPerCycle = 5;
	/** Schutz: nie mehr als so viel Prozent unter den bisherigen eigenen Preis gehen. */
	public int maxDropPercent = 15;
	/** Wie viele Seiten des Marktes (bei Suche) hoechstens gelesen werden, falls nicht nach Preis sortiert werden kann. */
	public int maxPagesMarket = 3;
	/** Wie viele Seiten der eigenen Auktionen hoechstens gelesen werden. */
	public int maxPagesOwn = 4;
	/** Wartezeit nach jedem Klick in Ticks (20 Ticks = 1 Sekunde). */
	public int clickDelayTicks = 4;
	/** true = Konkurrenz-Angebote mit anderer Stackgroesse werden ueber den Preis pro Item verglichen. */
	public boolean compareUnitPrice = true;
	/** Items, die automatisch unterboten werden (Item-IDs). */
	public List<String> items = new ArrayList<>(List.of("minecraft:pumpkin"));
	/** Mindestpreis pro Angebot. Schluessel "item-id@stackgroesse" oder nur "item-id". */
	public Map<String, Long> minPrice = new LinkedHashMap<>();
	/** Eigener Suchbegriff je Item-ID (sonst wird der Item-Name genommen). */
	public Map<String, String> searchTerms = new LinkedHashMap<>();
	/** Regex, mit dem die Sortier-Option "Preis aufsteigend" erkannt wird. */
	public String sortRegex = "(?i)(preis.*(aufsteigend|niedrig|g.nstig|billig))|((g.nstig|niedrig|billig).*preis)";

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("autoundercut.json");
	}

	public static Config load() {
		try {
			Path p = path();
			if (Files.exists(p)) {
				Config c = GSON.fromJson(Files.readString(p), Config.class);
				if (c != null) {
					c.fix();
					return c;
				}
			}
		} catch (Exception e) {
			AutoUndercutClient.LOG.warn("Konfiguration konnte nicht geladen werden, nutze Standardwerte", e);
		}
		Config c = new Config();
		c.save();
		return c;
	}

	public void save() {
		try {
			Files.writeString(path(), GSON.toJson(this));
		} catch (Exception e) {
			AutoUndercutClient.LOG.warn("Konfiguration konnte nicht gespeichert werden", e);
		}
	}

	private void fix() {
		if (items == null) items = new ArrayList<>();
		if (minPrice == null) minPrice = new LinkedHashMap<>();
		if (searchTerms == null) searchTerms = new LinkedHashMap<>();
		if (sortRegex == null || sortRegex.isBlank()) sortRegex = new Config().sortRegex;
		checkIntervalSeconds = Math.max(1, checkIntervalSeconds);
		undercut = Math.max(1, undercut);
		maxRelistsPerCycle = Math.max(0, maxRelistsPerCycle);
		maxDropPercent = Math.min(100, Math.max(0, maxDropPercent));
		maxPagesMarket = Math.max(1, maxPagesMarket);
		maxPagesOwn = Math.max(1, maxPagesOwn);
		clickDelayTicks = Math.max(1, clickDelayTicks);
	}

	/** Mindestpreis fuer dieses Item und diese Stackgroesse, oder 0. */
	public long minPriceFor(String itemId, int count) {
		Long v = minPrice.get(itemId + "@" + count);
		if (v == null) v = minPrice.get(itemId);
		return v == null ? 0 : v;
	}
}
