package com.autoundercut;

/** Ein Angebot, so wie es im Tooltip steht. slot = Index im Menue. */
public record Listing(String itemId, String name, int count, long price, String seller, int slot) {
}
