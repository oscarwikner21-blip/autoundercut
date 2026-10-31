package com.autoundercut;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Alle Zugriffe auf Minecraft-Fenster, Slots und Items an einer Stelle. */
final class Gui {
	private Gui() {
	}

	static Minecraft mc() {
		return Minecraft.getInstance();
	}

	// ---------------------------------------------------------------- Fenster

	static Screen screen() {
		return mc().gui.screen();
	}

	static AbstractContainerScreen<?> containerScreen() {
		return screen() instanceof AbstractContainerScreen<?> a ? a : null;
	}

	static AbstractContainerMenu menu() {
		AbstractContainerScreen<?> s = containerScreen();
		return s == null ? null : s.getMenu();
	}

	static List<Slot> slots() {
		AbstractContainerMenu m = menu();
		return m == null ? List.of() : m.slots;
	}

	/** Slots des Fensters ohne die 36 Inventar-Slots des Spielers am Ende. */
	static int containerSize() {
		return Math.max(0, slots().size() - 36);
	}

	static void closeIfOpen() {
		Screen s = screen();
		if (s instanceof AbstractContainerScreen<?> || s instanceof AbstractSignEditScreen) {
			s.onClose();
		}
	}

	// ---------------------------------------------------------------- Items

	static String id(ItemStack st) {
		return BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
	}

	static String name(ItemStack st) {
		return st.getHoverName().getString().trim();
	}

	/** Lore-Zeilen als Klartext (per Reflection, damit es bei kleinen API-Aenderungen nicht bricht). */
	static List<String> lore(ItemStack st) {
		List<String> out = new ArrayList<>();
		try {
			Object lore = st.get(DataComponents.LORE);
			if (lore == null) return out;
			Method m = lore.getClass().getMethod("lines");
			Object res = m.invoke(lore);
			if (res instanceof List<?> list) {
				for (Object o : list) {
					if (o instanceof Component c) out.add(c.getString());
				}
			}
		} catch (Exception e) {
			AutoUndercutClient.LOG.warn("Lore konnte nicht gelesen werden: {}", e.toString());
		}
		return out;
	}

	/** Die mit ">" markierte Zeile in einer Lore (aktuelle Auswahl bei Umschalt-Knoepfen). */
	static String selectedLine(ItemStack st) {
		for (String l : lore(st)) {
			String t = l.trim();
			if (t.startsWith(">")) return t.substring(1).trim();
		}
		return null;
	}

	static String selectedAt(int slot) {
		List<Slot> sl = slots();
		if (slot < 0 || slot >= sl.size()) return null;
		return selectedLine(sl.get(slot).getItem());
	}

	// ---------------------------------------------------------------- Knoepfe

	/** Index des ersten Container-Slots, dessen Item-Name komplett auf die Regex passt, sonst -1. */
	static int findButton(String regex) {
		Pattern p = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
		List<Slot> sl = slots();
		int n = containerSize();
		for (int i = 0; i < n; i++) {
			ItemStack st = sl.get(i).getItem();
			if (!st.isEmpty() && p.matcher(name(st)).matches()) return i;
		}
		return -1;
	}

	static boolean hasButton(String regex) {
		return findButton(regex) >= 0;
	}

	static void clickButton(String regex) throws Bot.Fail {
		int i = findButton(regex);
		if (i < 0) throw new Bot.Fail("Knopf nicht gefunden: " + regex);
		click(i);
	}

	// ---------------------------------------------------------------- Erkennung

	static boolean isAh() {
		return containerScreen() != null && (hasButton("auktion erstellen") || hasButton("deine auktionen.*"));
	}

	static boolean isSign() {
		return screen() instanceof AbstractSignEditScreen;
	}

	static boolean isAnvil() {
		AbstractContainerMenu m = menu();
		if (m != null && m.getClass().getSimpleName().toLowerCase().contains("anvil")) return true;
		Screen s = screen();
		return s != null && s.getClass().getSimpleName().toLowerCase().contains("anvil");
	}

	static String currentMode() {
		int i = findButton("deine auktionen.*");
		return i < 0 ? null : selectedAt(i);
	}

	// ---------------------------------------------------------------- Angebote lesen

	private static final Pattern PRICE = Pattern.compile("(?i)^\\s*preis\\s*:\\s*\\$?\\s*([0-9][0-9.,]*)");
	private static final Pattern SELLER = Pattern.compile("(?i)^\\s*verk\\S*ufer\\s*:\\s*(.+?)\\s*$");

	static long parsePrice(String s) {
		int comma = s.indexOf(',');
		if (comma >= 0) s = s.substring(0, comma);
		String digits = s.replaceAll("[^0-9]", "");
		if (digits.isEmpty() || digits.length() > 18) return -1;
		return Long.parseLong(digits);
	}

	static Listing parse(ItemStack st, int slot) {
		long price = -1;
		String seller = "";
		for (String line : lore(st)) {
			String s = line.trim();
			if (price < 0) {
				Matcher m = PRICE.matcher(s);
				if (m.find()) {
					price = parsePrice(m.group(1));
					continue;
				}
			}
			if (seller.isEmpty()) {
				Matcher m = SELLER.matcher(s);
				if (m.find()) seller = m.group(1);
			}
		}
		if (price < 0) return null;
		return new Listing(id(st), name(st), st.getCount(), price, seller, slot);
	}

	static List<Listing> readListings() {
		List<Listing> out = new ArrayList<>();
		List<Slot> sl = slots();
		int n = containerSize();
		for (int i = 0; i < n; i++) {
			ItemStack st = sl.get(i).getItem();
			if (st.isEmpty()) continue;
			Listing l = parse(st, i);
			if (l != null) out.add(l);
		}
		return out;
	}

	/** Fingerabdruck des Fensterinhalts ohne die laufende Restzeit (die sich jede Sekunde aendert). */
	static String signature() {
		StringBuilder sb = new StringBuilder();
		List<Slot> sl = slots();
		int n = containerSize();
		for (int i = 0; i < n; i++) {
			ItemStack st = sl.get(i).getItem();
			if (st.isEmpty()) continue;
			sb.append(i).append('|').append(id(st)).append('x').append(st.getCount()).append('|').append(name(st));
			Listing l = parse(st, i);
			if (l != null) sb.append('|').append(l.price()).append('|').append(l.seller());
			String sel = selectedLine(st);
			if (sel != null) sb.append("|>").append(sel);
			sb.append('\n');
		}
		return sb.toString();
	}

	// ---------------------------------------------------------------- Inventar

	/** Anzahl der Stacks mit dieser Item-ID und genau dieser Stackgroesse im Spieler-Inventar. */
	static int invCount(String itemId, int count) {
		if (mc().player == null) return 0;
		Inventory inv = mc().player.getInventory();
		int n = 0;
		for (int i = 0; i < 36; i++) {
			ItemStack st = inv.getItem(i);
			if (!st.isEmpty() && st.getCount() == count && id(st).equals(itemId)) n++;
		}
		return n;
	}

	static void pickupFromInventory(String itemId, int count) throws Bot.Fail {
		List<Slot> sl = slots();
		int cs = containerSize();
		for (int i = cs; i < sl.size(); i++) {
			ItemStack st = sl.get(i).getItem();
			if (!st.isEmpty() && st.getCount() == count && id(st).equals(itemId)) {
				click(i);
				return;
			}
		}
		throw new Bot.Fail("Stack nicht im Inventar gefunden: " + itemId + " x" + count);
	}

	static int selectorTarget() {
		List<Slot> sl = slots();
		int cs = containerSize();
		if (cs > 4 && sl.get(4).getItem().isEmpty()) return 4;
		for (int i = 0; i < cs; i++) {
			if (sl.get(i).getItem().isEmpty()) return i;
		}
		return -1;
	}

	static void dropIntoSelector() throws Bot.Fail {
		int t = selectorTarget();
		if (t < 0) throw new Bot.Fail("Kein freier Slot im Auswahlfenster");
		click(t);
	}

	static boolean selectorHolds(String itemId, int count) {
		List<Slot> sl = slots();
		int cs = containerSize();
		for (int i = 0; i < cs; i++) {
			ItemStack st = sl.get(i).getItem();
			if (!st.isEmpty() && st.getCount() == count && id(st).equals(itemId)) return true;
		}
		return false;
	}

	// ---------------------------------------------------------------- Klicks

	static void click(int slot) throws Bot.Fail {
		Minecraft mc = mc();
		AbstractContainerMenu menu = menu();
		if (menu == null || mc.gameMode == null || mc.player == null) throw new Bot.Fail("Kein Menü offen");
		if (slot < 0 || slot >= menu.slots.size()) throw new Bot.Fail("Ungültiger Slot " + slot);
		try {
			mc.gameMode.handleContainerInput(menu.containerId, slot, 0, ContainerInput.PICKUP, mc.player);
		} catch (Exception e) {
			throw new Bot.Fail("Klick fehlgeschlagen: " + e);
		}
	}

	// ---------------------------------------------------------------- Schild (Suche)

	static void fillSignAndClose(String term) throws Bot.Fail {
		Screen s = screen();
		if (!(s instanceof AbstractSignEditScreen)) throw new Bot.Fail("Kein Schild-Fenster offen");
		try {
			for (Class<?> c = s.getClass(); c != null; c = c.getSuperclass()) {
				for (Field f : c.getDeclaredFields()) {
					if (f.getType() == String[].class) {
						f.setAccessible(true);
						String[] arr = (String[]) f.get(s);
						if (arr != null && arr.length >= 1) {
							arr[0] = term;
							s.onClose();
							return;
						}
					}
				}
			}
		} catch (Exception e) {
			throw new Bot.Fail("Schild konnte nicht beschrieben werden: " + e);
		}
		throw new Bot.Fail("Textzeilen des Schilds nicht gefunden");
	}

	// ---------------------------------------------------------------- Amboss (Preis)

	static void setAnvilText(String text) throws Bot.Fail {
		Screen s = screen();
		if (s == null) throw new Bot.Fail("Kein Fenster offen");
		try {
			for (Class<?> c = s.getClass(); c != null; c = c.getSuperclass()) {
				for (Field f : c.getDeclaredFields()) {
					if (f.getType().getSimpleName().equals("EditBox")) {
						f.setAccessible(true);
						Object box = f.get(s);
						if (box != null) {
							Method m = box.getClass().getMethod("setValue", String.class);
							m.invoke(box, text);
							return;
						}
					}
				}
			}
		} catch (Exception e) {
			AutoUndercutClient.LOG.warn("Amboss-Textfeld nicht beschreibbar: {}", e.toString());
			return; // Ersatzweg: Umbenennen-Paket (siehe sendRenamePacket)
		}
		AutoUndercutClient.LOG.warn("Kein EditBox-Feld im Amboss-Fenster gefunden");
	}

	/** Ersatzweg: sendet den Text direkt als Amboss-Umbenennung an den Server. */
	static void sendRenamePacket(String text) {
		try {
			Object conn = mc().player.connection;
			Class<?> packetClass = Class.forName("net.minecraft.network.protocol.game.ServerboundRenameItemPacket");
			Object pkt = packetClass.getConstructor(String.class).newInstance(text);
			Class<?> base = Class.forName("net.minecraft.network.protocol.Packet");
			conn.getClass().getMethod("send", base).invoke(conn, pkt);
		} catch (Throwable t) {
			AutoUndercutClient.LOG.warn("Rename-Paket konnte nicht gesendet werden: {}", t.toString());
		}
	}

	static String resultSig() {
		List<Slot> sl = slots();
		if (sl.size() <= 2) return "none";
		ItemStack st = sl.get(2).getItem();
		if (st.isEmpty()) return "empty";
		return id(st) + "|" + name(st) + "|" + st.getCount() + "|" + String.join("/", lore(st));
	}

	static boolean resultNonEmpty() {
		List<Slot> sl = slots();
		return sl.size() > 2 && !sl.get(2).getItem().isEmpty();
	}

	// ---------------------------------------------------------------- Debug

	static Path dump(String reason) {
		try {
			StringBuilder sb = new StringBuilder();
			sb.append("Grund: ").append(reason).append('\n');
			Screen s = screen();
			sb.append("Screen: ").append(s == null ? "keiner" : s.getClass().getName()).append('\n');
			if (s != null) sb.append("Titel: ").append(s.getTitle().getString()).append('\n');
			AbstractContainerMenu m = menu();
			if (m != null) {
				sb.append("Menu: ").append(m.getClass().getName()).append("   Slots gesamt: ").append(m.slots.size()).append('\n');
				int cs = containerSize();
				sb.append("--- Fenster-Slots (0 bis ").append(cs - 1).append(") ---\n");
				for (int i = 0; i < m.slots.size(); i++) {
					ItemStack st = m.slots.get(i).getItem();
					if (i == cs) sb.append("--- Spieler-Inventar (nur belegte Slots) ---\n");
					if (st.isEmpty()) {
						if (i < cs) sb.append(i).append(": (leer)\n");
						continue;
					}
					sb.append(i).append(": ").append(id(st)).append(" x").append(st.getCount()).append("  Name='").append(name(st)).append("'\n");
					if (i < cs) {
						for (String l : lore(st)) sb.append("      | ").append(l).append('\n');
					}
				}
			}
			Path p = FabricLoader.getInstance().getConfigDir().resolve("autoundercut-debug.txt");
			Files.writeString(p, sb.toString());
			return p;
		} catch (Exception e) {
			AutoUndercutClient.LOG.warn("Debug-Datei konnte nicht geschrieben werden", e);
			return null;
		}
	}
}
