package me.drex.polymerpatcher.compat.subtlyd;

import me.drex.polymerpatcher.PolymerPatcher;

/**
 * Subtly Dungeons registers its {@code minecraft:remove_effects} consume effect type from a static
 * initializer that nothing on a server runs until the first item carrying the effect is encoded.
 * That happens inside Polymer's {@code sync/items} payload - by then {@code CONSUME_EFFECT_TYPE}
 * is frozen, the registration throws, Polymer swallows it and every joining client is handed a
 * truncated item payload and disconnected. Forcing the class to initialize while registries are
 * still writable makes the registration succeed where Subtly Dungeons left it.
 */
public final class SubtlydCompatibility {
	private static final String CONSUME_EFFECT_TYPE_CLASS =
		"net.meander.subtlyd.world.item.consume_effects.ConsumeEffectSD$Type";
	private static final String CONSUME_EFFECT_CLASS =
		"net.meander.subtlyd.world.item.consume_effects.RemoveNegativeStatusEffectsConsumeEffect";

	private SubtlydCompatibility() {
	}

	public static void init() {
		for (String clazz : new String[] { CONSUME_EFFECT_TYPE_CLASS, CONSUME_EFFECT_CLASS }) {
			try {
				Class.forName(clazz);
			} catch (ClassNotFoundException e) {
				// Subtly Dungeons is not installed, or a class it registers does not exist; nothing
				// further to warm up for it.
			} catch (Throwable e) {
				PolymerPatcher.LOGGER.warn(
					"Could not pre-register Subtly Dungeons' consume effect type; joining players may be "
						+ "kicked if the item sync runs into it after registries freeze", e);
			}
		}
	}
}