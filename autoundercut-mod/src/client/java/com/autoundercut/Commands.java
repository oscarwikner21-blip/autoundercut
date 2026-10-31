package com.autoundercut;

import java.nio.file.Path;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

final class Commands {
	private Commands() {
	}

	private static void say(CommandContext<FabricClientCommandSource> c, String text) {
		c.getSource().sendFeedback(Component.literal("\u00a7b[AutoUndercut]\u00a7r " + text));
	}

	private static ItemStack hand() {
		var p = Gui.mc().player;
		return p == null ? ItemStack.EMPTY : p.getMainHandItem();
	}

	static void register(CommandDispatcher<FabricClientCommandSource> d, Bot bot) {
		Config cfg = bot.cfg();
		d.register(ClientCommands.literal("autoah")
			.executes(c -> {
				say(c, "Befehle: on, off, status, once, list, debug, sell <item|hand> <preis> <stack|half|all>, set <wert> <zahl>, add [item], remove [item], min <preis>");
				return 1;
			})
			.then(ClientCommands.literal("on").executes(c -> {
				bot.setEnabled(true);
				say(c, "Eingeschaltet. " + bot.status());
				if (cfg.dryRun) say(c, "\u00a76Testlauf aktiv: es wird nur angezeigt, was passieren würde. Scharf schalten mit /autoah set dryrun false");
				return 1;
			}))
			.then(ClientCommands.literal("off").executes(c -> {
				bot.setEnabled(false);
				say(c, "Ausgeschaltet.");
				return 1;
			}))
			.then(ClientCommands.literal("status").executes(c -> {
				say(c, bot.status());
				return 1;
			}))
			.then(ClientCommands.literal("once").executes(c -> {
				bot.runOnce();
				say(c, "Ein Durchgang startet gleich (" + (cfg.dryRun ? "Testlauf" : "scharf") + ").");
				return 1;
			}))
			.then(ClientCommands.literal("list").executes(c -> {
				say(c, "Items: " + cfg.items);
				say(c, "Unterbieten um: $" + Bot.fmt(cfg.undercut) + " | Stacks pro Durchgang: " + cfg.maxRelistsPerCycle
						+ " | Prüfintervall: " + cfg.checkIntervalSeconds + "s | Schutz: max. " + cfg.maxDropPercent + "% runter");
				say(c, "Mindestpreise: " + (cfg.minPrice.isEmpty() ? "keine" : cfg.minPrice.toString()));
				say(c, "Preis pro Item vergleichen: " + cfg.compareUnitPrice);
				say(c, "Testlauf: " + cfg.dryRun + " | Seiten Markt/eigene: " + cfg.maxPagesMarket + "/" + cfg.maxPagesOwn
						+ " | Klick-Pause: " + cfg.clickDelayTicks + " Ticks");
				return 1;
			}))
			.then(ClientCommands.literal("debug").executes(c -> {
				Path p = Gui.dump("manuell");
				say(c, p == null ? "Konnte die Debug-Datei nicht schreiben." : "Geschrieben: " + p
						+ " (öffne dafür vorher das /ah-Fenster, sonst steht nur 'kein Fenster' drin)");
				return 1;
			}))
			.then(ClientCommands.literal("sell")
				.then(ClientCommands.argument("item", StringArgumentType.word())
					.then(ClientCommands.argument("price", LongArgumentType.longArg(1))
						.then(ClientCommands.literal("stack").executes(c -> sell(c, bot, "stack")))
						.then(ClientCommands.literal("half").executes(c -> sell(c, bot, "half")))
						.then(ClientCommands.literal("all").executes(c -> sell(c, bot, "all"))))))
			.then(ClientCommands.literal("set")
				.then(ClientCommands.argument("key", StringArgumentType.word())
					.then(ClientCommands.argument("value", StringArgumentType.word())
						.executes(c -> {
							say(c, setValue(cfg, StringArgumentType.getString(c, "key"), StringArgumentType.getString(c, "value")));
							return 1;
						}))))
			.then(ClientCommands.literal("add")
				.executes(c -> addItem(c, cfg, Gui.id(hand()), hand().isEmpty()))
				.then(ClientCommands.argument("item", StringArgumentType.greedyString())
					.executes(c -> addItem(c, cfg, StringArgumentType.getString(c, "item").trim(), false))))
			.then(ClientCommands.literal("remove")
				.executes(c -> removeItem(c, cfg, Gui.id(hand()), hand().isEmpty()))
				.then(ClientCommands.argument("item", StringArgumentType.greedyString())
					.executes(c -> removeItem(c, cfg, StringArgumentType.getString(c, "item").trim(), false))))
			.then(ClientCommands.literal("min")
				.then(ClientCommands.argument("price", LongArgumentType.longArg(0))
					.executes(c -> {
						ItemStack h = hand();
						if (h.isEmpty()) {
							say(c, "\u00a7cHalte das Item in der Hand, für das der Mindestpreis gelten soll.");
							return 0;
						}
						long price = LongArgumentType.getLong(c, "price");
						String key = Gui.id(h) + "@" + h.getCount();
						if (price == 0) {
							cfg.minPrice.remove(key);
							say(c, "Mindestpreis für " + key + " entfernt.");
						} else {
							cfg.minPrice.put(key, price);
							say(c, "Mindestpreis für " + key + ": $" + Bot.fmt(price) + " pro Angebot");
						}
						cfg.save();
						return 1;
					}))));
	}

	private static int sell(CommandContext<FabricClientCommandSource> c, Bot bot, String mode) {
		String raw = StringArgumentType.getString(c, "item").trim().toLowerCase();
		long price = LongArgumentType.getLong(c, "price");
		String itemId;
		boolean fromHand = false;
		if (raw.equals("hand")) {
			ItemStack h = hand();
			if (h.isEmpty()) {
				say(c, "\u00a7cDu hast nichts in der Hand. Nimm das Item in die Hand oder schreibe z. B. pumpkin statt hand.");
				return 0;
			}
			itemId = Gui.id(h);
			fromHand = true;
		} else {
			itemId = raw.contains(":") ? raw : "minecraft:" + raw;
		}
		String err = bot.startSell(itemId, price, mode, fromHand);
		if (err != null) {
			say(c, "\u00a7c" + err);
			return 0;
		}
		return 1;
	}

	private static int addItem(CommandContext<FabricClientCommandSource> c, Config cfg, String id, boolean noItem) {
		if (noItem) {
			say(c, "\u00a7cHalte ein Item in der Hand oder schreibe die ID, z. B. /autoah add minecraft:pumpkin");
			return 0;
		}
		if (!cfg.items.contains(id)) cfg.items.add(id);
		cfg.save();
		say(c, "Wird unterboten: " + cfg.items);
		return 1;
	}

	private static int removeItem(CommandContext<FabricClientCommandSource> c, Config cfg, String id, boolean noItem) {
		if (noItem) {
			say(c, "\u00a7cHalte ein Item in der Hand oder schreibe die ID.");
			return 0;
		}
		cfg.items.remove(id);
		cfg.save();
		say(c, "Wird unterboten: " + cfg.items);
		return 1;
	}

	private static String setValue(Config cfg, String key, String value) {
		try {
			switch (key.toLowerCase()) {
				case "dryrun" -> cfg.dryRun = Boolean.parseBoolean(value);
				case "unitcompare" -> cfg.compareUnitPrice = Boolean.parseBoolean(value);
				case "interval" -> cfg.checkIntervalSeconds = Math.max(1, Integer.parseInt(value));
				case "undercut" -> cfg.undercut = Math.max(1, Long.parseLong(value));
				case "stacks" -> cfg.maxRelistsPerCycle = Math.max(0, Integer.parseInt(value));
				case "drop" -> cfg.maxDropPercent = Math.min(100, Math.max(0, Integer.parseInt(value)));
				case "marketpages" -> cfg.maxPagesMarket = Math.max(1, Integer.parseInt(value));
				case "ownpages" -> cfg.maxPagesOwn = Math.max(1, Integer.parseInt(value));
				case "delay" -> cfg.clickDelayTicks = Math.max(1, Integer.parseInt(value));
				default -> {
					return "\u00a7cUnbekannt: " + key + ". Möglich: dryrun, unitcompare, interval, undercut, stacks, drop, marketpages, ownpages, delay";
				}
			}
		} catch (NumberFormatException e) {
			return "\u00a7cKeine gültige Zahl: " + value;
		}
		cfg.save();
		return "Gesetzt: " + key + " = " + value;
	}
}
