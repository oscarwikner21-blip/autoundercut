package com.autoundercut;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public final class AutoUndercutClient implements ClientModInitializer {
	public static final Logger LOG = LoggerFactory.getLogger("AutoUndercut");
	public static Bot BOT;

	@Override
	public void onInitializeClient() {
		Config cfg = Config.load();
		BOT = new Bot(cfg);
		ClientTickEvents.END_CLIENT_TICK.register(client -> BOT.tick());
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> Commands.register(dispatcher, BOT));
		LOG.info("Auto Undercut geladen. Befehl: /autoah");
	}
}
