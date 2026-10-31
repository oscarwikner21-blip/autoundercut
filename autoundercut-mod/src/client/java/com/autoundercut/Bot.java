package com.autoundercut;

import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Steuert den Ablauf. Alles passiert tick-weise (20x pro Sekunde) als Warteschlange von Schritten.
 *
 * Ein Durchgang:
 *  1. /ah oeffnen, auf "Laufende Auktionen" schalten, eigene Angebote lesen
 *  2. auf "Alle Auktionen" schalten, je Item suchen (Schild), Markt lesen
 *  3. entscheiden, welche eigenen Angebote zu teuer sind
 *  4. je Angebot: stornieren, neu einstellen (Item waehlen, Preis im Amboss, bestaetigen)
 */
public final class Bot {
	public static class Fail extends Exception {
		public Fail(String m) {
			super(m);
		}
	}

	/** Nicht so schlimm: aktuellen Auftrag ueberspringen, weitermachen. */
	public static final class Skip extends Fail {
		public Skip(String m) {
			super(m);
		}
	}

	interface Step {
		/** true = fertig. */
		boolean tick(Bot b) throws Exception;
	}

	interface Act {
		void run() throws Exception;
	}

	interface Dyn {
		void run(Bot b) throws Exception;
	}

	/** Markiert das Ende eines Auftrags (zum Ueberspringen). */
	static final class Marker implements Step {
		@Override
		public boolean tick(Bot b) {
			return true;
		}
	}

	record Job(String itemId, String name, int count, long oldPrice, long newPrice) {
	}

	private static final class Ctx {
		final List<Listing> mine = new ArrayList<>();
		List<Listing> myWatched = new ArrayList<>();
		final Map<String, List<Listing>> market = new HashMap<>();
		boolean sorted;
	}

	// Knopf-Namen auf dem Server (als Regex, ohne Gross-/Kleinschreibung)
	static final String R_SEARCH = "suche";
	static final String R_CREATE = "auktion erstellen";
	static final String R_NEXT = "n.chste seite";
	static final String R_MINE = "deine auktionen.*";
	static final String R_SETPRICE = "preis festlegen";
	static final String R_LIST_OK = "angebot erstellen";
	static final String R_CANCEL_OK = "stornieren";
	static final String MODE_ALL = "Alle Auktionen anzeigen";
	static final String MODE_RUNNING = "Laufende Auktionen";
	static final String R_SORT_BUTTON = ".*(filter|sortier).*";

	private final Config cfg;
	private final ArrayDeque<Step> queue = new ArrayDeque<>();
	private final Map<String, String> lastNotes = new HashMap<>();
	private Ctx ctx = new Ctx();
	private boolean enabled;
	private boolean once;
	private boolean cycleActive;
	private int idle;
	private int failStreak;

	public Bot(Config cfg) {
		this.cfg = cfg;
	}

	public Config cfg() {
		return cfg;
	}

	// ------------------------------------------------------------------ Steuerung

	public boolean isEnabled() {
		return enabled;
	}

	public boolean isBusy() {
		return cycleActive;
	}

	public void setEnabled(boolean on) {
		enabled = on;
		if (on) {
			failStreak = 0;
			idle = 0;
		} else {
			abort();
		}
	}

	public void runOnce() {
		once = true;
		failStreak = 0;
		idle = 0;
	}

	public void abort() {
		if (cycleActive) {
			queue.clear();
			cycleActive = false;
			Gui.closeIfOpen();
		}
	}

	public String status() {
		return (enabled ? "AN" : "AUS") + " | " + (cfg.dryRun ? "TESTLAUF (ändert nichts)" : "SCHARF")
				+ " | " + (cycleActive ? "Durchgang läuft" : "wartet");
	}

	static void chat(String text) {
		try {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player != null) {
				mc.gui.hud.getChat().addClientSystemMessage(Component.literal("\u00a7b[AutoUndercut]\u00a7r " + text));
			}
		} catch (Throwable t) {
			AutoUndercutClient.LOG.info(text);
		}
	}

	private void note(String key, String text) {
		if (!text.equals(lastNotes.put(key, text))) chat(text);
	}

	static String fmt(long v) {
		return NumberFormat.getIntegerInstance(Locale.GERMANY).format(v);
	}

	// ------------------------------------------------------------------ Tick

	public void tick() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			if (cycleActive) {
				queue.clear();
				cycleActive = false;
			}
			return;
		}
		if (cycleActive) {
			runStep();
			return;
		}
		if (!enabled && !once) return;
		if (idle > 0) {
			idle--;
			return;
		}
		if (mc.gui.screen() != null) return; // du machst gerade etwas anderes
		once = false;
		startCycle();
	}

	private void runStep() {
		Step cur = queue.pollFirst();
		if (cur == null) {
			finishOk();
			return;
		}
		try {
			if (!cur.tick(this)) queue.addFirst(cur);
		} catch (Skip s) {
			chat("\u00a7eÜbersprungen: " + s.getMessage());
			while (!queue.isEmpty() && !(queue.peekFirst() instanceof Marker)) queue.pollFirst();
			queue.pollFirst();
		} catch (Fail f) {
			fail(f.getMessage());
		} catch (Exception e) {
			AutoUndercutClient.LOG.error("Fehler im Ablauf", e);
			fail("Interner Fehler: " + e);
		}
	}

	private void fail(String msg) {
		Path p = Gui.dump(msg);
		queue.clear();
		cycleActive = false;
		Gui.closeIfOpen();
		failStreak++;
		chat("\u00a7cFehler: " + msg);
		if (p != null) chat("\u00a77Debug-Datei geschrieben: " + p);
		if (failStreak >= 5) {
			enabled = false;
			chat("\u00a7c5 Fehler in Folge – Mod ausgeschaltet.");
		}
		idle = Math.max(cfg.checkIntervalSeconds, 15) * 20;
	}

	private void finishOk() {
		failStreak = 0;
		queue.clear();
		cycleActive = false;
		Gui.closeIfOpen();
		idle = Math.max(1, cfg.checkIntervalSeconds) * 20;
	}

	// ------------------------------------------------------------------ Schritt-Bausteine

	private int d() {
		return cfg.clickDelayTicks;
	}

	static Step pause(int ticks) {
		int[] c = {0};
		return b -> ++c[0] >= ticks;
	}

	static Step act(Act a) {
		return b -> {
			a.run();
			return true;
		};
	}

	static Step dyn(Dyn x) {
		return b -> {
			x.run(b);
			return true;
		};
	}

	static Step until(String what, int timeout, BooleanSupplier cond) {
		int[] c = {0};
		return b -> {
			if (cond.getAsBoolean()) return true;
			if (++c[0] > timeout) throw new Fail("Zeitüberschreitung: " + what);
			return false;
		};
	}

	/** Wartet, bis sich now() von before() unterscheidet, hoechstens timeout Ticks (kein Fehler beim Timeout). */
	static Step softChange(Supplier<String> now, Supplier<String> before, int timeout, boolean[] changed) {
		int[] c = {0};
		return b -> {
			if (!Objects.equals(now.get(), before.get())) {
				changed[0] = true;
				return true;
			}
			return ++c[0] >= timeout;
		};
	}

	void next(List<Step> steps) {
		for (int i = steps.size() - 1; i >= 0; i--) queue.addFirst(steps.get(i));
	}

	void next(Step... steps) {
		next(Arrays.asList(steps));
	}

	// ------------------------------------------------------------------ Durchgang

	private void startCycle() {
		cycleActive = true;
		ctx = new Ctx();
		queue.clear();
		List<Step> s = new ArrayList<>();
		openAh(s);
		ensureMode(s, MODE_RUNNING);
		s.add(dyn(b -> b.scanPage(cfg.maxPagesOwn, 1, ctx.mine)));
		s.add(dyn(b -> b.afterOwnScan()));
		queue.addAll(s);
	}

	private void openAh(List<Step> s) {
		s.add(act(Gui::closeIfOpen));
		s.add(pause(d() + 2));
		s.add(act(() -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player == null) throw new Fail("Nicht verbunden");
			mc.player.connection.sendCommand("ah");
		}));
		s.add(until("/ah öffnet sich nicht", 120, Gui::isAh));
		s.add(pause(d()));
	}

	private void ensureMode(List<Step> s, String wanted) {
		s.add(dyn(b -> b.modeLoop(wanted, 0)));
	}

	private void modeLoop(String wanted, int attempt) throws Fail {
		String cur = Gui.currentMode();
		if (cur == null) throw new Fail("Umschalter 'Deine Auktionen' (orange) nicht gefunden");
		if (cur.equalsIgnoreCase(wanted)) return;
		if (attempt >= 6) throw new Fail("Ansicht '" + wanted + "' nicht erreichbar (aktuell: " + cur + ")");
		next(
			act(() -> Gui.clickButton(R_MINE)),
			pause(d()),
			until("Ansicht wechselt nicht", 80, () -> {
				String m = Gui.currentMode();
				return m != null && !m.equalsIgnoreCase(cur);
			}),
			pause(d()),
			dyn(b -> b.modeLoop(wanted, attempt + 1)));
	}

	/** Liest die aktuelle Seite und blaettert bis max Seiten weiter. */
	private void scanPage(int max, int page, List<Listing> out) throws Fail {
		out.addAll(Gui.readListings());
		if (page >= max) return;
		int nb = Gui.findButton(R_NEXT);
		if (nb < 0) return;
		String before = Gui.signature();
		boolean[] ch = new boolean[1];
		next(
			act(() -> Gui.click(nb)),
			pause(d()),
			softChange(Gui::signature, () -> before, 40, ch),
			dyn(b -> {
				if (ch[0]) b.scanPage(max, page + 1, out);
			}));
	}

	private void search(List<Step> s, String term) {
		String[] before = new String[1];
		s.add(act(() -> before[0] = Gui.signature()));
		s.add(act(() -> Gui.clickButton(R_SEARCH)));
		s.add(until("Such-Schild öffnet sich nicht", 80, Gui::isSign));
		s.add(pause(d()));
		s.add(act(() -> Gui.fillSignAndClose(term)));
		s.add(pause(10));
		s.add(until("Auktionsmenü kam nach der Suche nicht zurück", 120, Gui::isAh));
		s.add(softChange(Gui::signature, () -> before[0], 40, new boolean[1]));
		s.add(pause(d()));
	}

	/** Versucht (falls es einen durchschaltbaren Sortier-Knopf gibt), nach Preis aufsteigend zu sortieren. */
	private void trySort(int attempt) {
		if (attempt == 0) ctx.sorted = false;
		int idx = Gui.findButton(R_SORT_BUTTON);
		if (idx < 0) return;
		String sel = Gui.selectedAt(idx);
		if (sel == null) return; // kein Umschalter -> nicht anfassen
		if (Pattern.compile(cfg.sortRegex).matcher(sel).find()) {
			ctx.sorted = true;
			return;
		}
		if (attempt >= 8) return;
		boolean[] ch = new boolean[1];
		next(
			act(() -> Gui.click(idx)),
			pause(d()),
			softChange(() -> Gui.selectedAt(idx), () -> sel, 40, ch),
			pause(d()),
			dyn(b -> {
				if (ch[0]) b.trySort(attempt + 1);
			}));
	}

	private void afterOwnScan() throws Fail {
		List<Listing> watched = new ArrayList<>();
		for (Listing l : ctx.mine) {
			if (cfg.items.contains(l.itemId())) watched.add(l);
		}
		ctx.myWatched = watched;
		if (watched.isEmpty()) {
			note("idle", "Keine überwachten Items im Verkauf (" + ctx.mine.size() + " Angebote gelesen).");
			finishOk();
			return;
		}
		Map<String, String> ids = new LinkedHashMap<>();
		for (Listing l : watched) ids.putIfAbsent(l.itemId(), l.name());

		List<Step> s = new ArrayList<>();
		ensureMode(s, MODE_ALL);
		for (Map.Entry<String, String> e : ids.entrySet()) {
			String id = e.getKey();
			String term = cfg.searchTerms.getOrDefault(id, e.getValue());
			if (term.length() < 3) throw new Fail("Suchbegriff für " + id + " ist zu kurz – trage in der Config unter searchTerms einen ein");
			List<Listing> bucket = new ArrayList<>();
			ctx.market.put(id, bucket);
			search(s, term);
			s.add(dyn(b -> b.trySort(0)));
			s.add(dyn(b -> b.scanPage(ctx.sorted ? 1 : cfg.maxPagesMarket, 1, bucket)));
		}
		s.add(dyn(b -> b.decide()));
		next(s);
	}

	private static boolean isMine(Listing l, String me) {
		return l.seller().equalsIgnoreCase("du") || l.seller().equalsIgnoreCase(me);
	}

	private void decide() throws Fail {
		String me = Minecraft.getInstance().player.getName().getString();
		Map<String, List<Listing>> groups = new LinkedHashMap<>();
		for (Listing l : ctx.myWatched) {
			groups.computeIfAbsent(l.itemId() + "|" + l.count() + "|" + l.price(), k -> new ArrayList<>()).add(l);
		}
		List<Job> jobs = new ArrayList<>();
		for (List<Listing> g : groups.values()) {
			Listing own = g.get(0);
			long minComp = Long.MAX_VALUE;
			for (Listing m : ctx.market.getOrDefault(own.itemId(), List.of())) {
				if (!m.itemId().equals(own.itemId()) || m.count() != own.count()) continue;
				if (isMine(m, me)) continue;
				minComp = Math.min(minComp, m.price());
			}
			if (minComp == Long.MAX_VALUE) continue; // kein Konkurrent gesehen
			if (own.price() < minComp) continue; // du bist schon der Günstigste
			String label = own.name() + " x" + own.count();
			String key = own.itemId() + "|" + own.count() + "|" + own.price();
			long target = minComp - cfg.undercut;
			if (target < 1) continue;
			long minP = cfg.minPriceFor(own.itemId(), own.count());
			if (minP > 0 && target < minP) {
				note(key, "\u00a7e" + label + ": Konkurrent bei $" + fmt(minComp) + ", dein Mindestpreis $" + fmt(minP) + " verhindert das Unterbieten.");
				continue;
			}
			long floor = own.price() * (100 - cfg.maxDropPercent) / 100;
			if (target < floor) {
				note(key, "\u00a7e" + label + ": Konkurrent bei $" + fmt(minComp) + " liegt mehr als " + cfg.maxDropPercent
						+ "% unter deinem Preis $" + fmt(own.price()) + " – übersprungen.");
				continue;
			}
			note(key, (cfg.dryRun ? "\u00a76[Testlauf] " : "\u00a7a") + label + ": du $" + fmt(own.price()) + ", Konkurrent $" + fmt(minComp)
					+ " → neuer Preis $" + fmt(target) + " (" + g.size() + " Stack" + (g.size() == 1 ? "" : "s") + ")");
			for (Listing l : g) jobs.add(new Job(l.itemId(), l.name(), l.count(), l.price(), target));
		}
		if (jobs.isEmpty() || cfg.dryRun) {
			finishOk();
			return;
		}
		jobs.sort(Comparator.comparingLong(Job::oldPrice).reversed());
		if (jobs.size() > cfg.maxRelistsPerCycle) jobs = new ArrayList<>(jobs.subList(0, cfg.maxRelistsPerCycle));
		if (jobs.isEmpty()) {
			finishOk();
			return;
		}
		List<Step> s = new ArrayList<>();
		for (Job j : jobs) repriceJob(s, j);
		s.add(dyn(b -> b.finishOk()));
		next(s);
	}

	// ------------------------------------------------------------------ Ein Angebot neu einstellen

	private void repriceJob(List<Step> s, Job j) {
		int[] inv = new int[2];

		// 1) eigenes Angebot finden und stornieren
		openAh(s);
		ensureMode(s, MODE_RUNNING);
		s.add(dyn(b -> b.locateAndCancel(j, 1, inv)));
		s.add(act(() -> inv[1] = Gui.invCount(j.itemId(), j.count())));

		// 2) neu einstellen
		openAh(s);
		s.add(act(() -> Gui.clickButton(R_CREATE)));
		s.add(until("Item-Auswahl öffnet sich nicht", 80, () -> Gui.hasButton(R_SETPRICE)));
		s.add(pause(d()));
		s.add(act(() -> Gui.pickupFromInventory(j.itemId(), j.count())));
		s.add(pause(d()));
		s.add(act(Gui::dropIntoSelector));
		s.add(pause(d() + 2));
		s.add(until("Item liegt nicht im Auswahlfeld", 40, () -> Gui.selectorHolds(j.itemId(), j.count())));
		s.add(act(() -> Gui.clickButton(R_SETPRICE)));
		s.add(until("Preis-Eingabe (Amboss) öffnet sich nicht", 100, Gui::isAnvil));
		s.add(pause(d() + 6));
		String[] sig = new String[1];
		s.add(act(() -> sig[0] = Gui.resultSig()));
		s.add(act(() -> Gui.setAnvilText(Long.toString(j.newPrice()))));
		s.add(priceAccepted(sig, j.newPrice()));
		s.add(pause(d()));
		s.add(act(() -> Gui.click(2)));
		s.add(until("Bestätigung fürs neue Angebot öffnet sich nicht", 100, () -> Gui.hasButton(R_LIST_OK)));
		s.add(pause(d()));
		s.add(act(() -> Gui.clickButton(R_LIST_OK)));
		s.add(pause(20));
		s.add(until("Angebot wurde nicht erstellt (Stack liegt noch im Inventar)", 100,
				() -> Gui.invCount(j.itemId(), j.count()) < inv[1]));
		s.add(act(() -> chat("\u00a7aNeu eingestellt: " + j.name() + " x" + j.count() + "  $" + fmt(j.oldPrice()) + " → $" + fmt(j.newPrice()))));
		s.add(new Marker());
	}

	/** Wartet, bis der Amboss das Ergebnis aendert; falls nicht, wird zusaetzlich das Umbenennen-Paket geschickt. */
	private Step priceAccepted(String[] sig, long price) {
		int[] c = {0};
		boolean[] sent = {false};
		return b -> {
			c[0]++;
			if (!Gui.resultSig().equals(sig[0]) && Gui.resultNonEmpty()) return true;
			if (c[0] == 12 && !sent[0]) {
				sent[0] = true;
				Gui.sendRenamePacket(Long.toString(price));
			}
			if (c[0] > 80) throw new Fail("Preis wurde im Amboss-Fenster nicht akzeptiert");
			return false;
		};
	}

	private void locateAndCancel(Job j, int page, int[] inv) throws Fail {
		for (Listing l : Gui.readListings()) {
			if (l.itemId().equals(j.itemId()) && l.count() == j.count() && l.price() == j.oldPrice()) {
				inv[0] = Gui.invCount(j.itemId(), j.count());
				int slot = l.slot();
				next(
					act(() -> Gui.click(slot)),
					pause(d()),
					until("Bestätigungsfenster zum Stornieren öffnet sich nicht", 80, () -> Gui.hasButton(R_CANCEL_OK)),
					pause(d()),
					act(() -> Gui.clickButton(R_CANCEL_OK)),
					until("Stack kam nicht ins Inventar zurück (Inventar voll?)", 120,
							() -> Gui.invCount(j.itemId(), j.count()) > inv[0]),
					pause(d()));
				return;
			}
		}
		int nb = Gui.findButton(R_NEXT);
		if (page >= cfg.maxPagesOwn || nb < 0) throw new Skip("Angebot nicht mehr gefunden (vielleicht gerade verkauft).");
		String before = Gui.signature();
		boolean[] ch = new boolean[1];
		next(
			act(() -> Gui.click(nb)),
			pause(d()),
			softChange(Gui::signature, () -> before, 40, ch),
			dyn(b -> {
				if (!ch[0]) throw new Skip("Angebot nicht mehr gefunden (vielleicht gerade verkauft).");
				b.locateAndCancel(j, page + 1, inv);
			}));
	}
}
