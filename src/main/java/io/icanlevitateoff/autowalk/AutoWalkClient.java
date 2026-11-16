package io.icanlevitateoff.autowalk;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Writer;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * AutoWalk - Fabric port of wwrpg's AutoWalk mod, for Minecraft 26.1 - 26.3.
 *
 * <ul>
 *   <li>Auto Walk (R) - toggles holding forward; tapping forward cancels it</li>
 *   <li>Auto Pick (B) - toggles holding attack</li>
 *   <li>Auto Jump (G) - toggles holding jump</li>
 * </ul>
 *
 * One jar covers every 26.x release. The two things that differ between them are
 * handled at runtime:
 * <ul>
 *   <li>26.3 moved from GLFW to SDL3, so default key codes are read from
 *       {@link InputConstants} at runtime rather than inlined at compile time.</li>
 *   <li>26.3 replaced the {@code Minecraft.screen} field with {@code Minecraft.gui.screen()},
 *       so the open screen is looked up through whichever one exists.</li>
 * </ul>
 */
public class AutoWalkClient implements ClientModInitializer {
	public static final String MOD_ID = "autowalk";
	private static final Logger LOGGER = LoggerFactory.getLogger("AutoWalk");

	private KeyMapping autoWalkKey;
	private KeyMapping autoPickKey;
	private KeyMapping autoJumpKey;

	private boolean walking;
	private boolean picking;
	private boolean jumping;

	// Whether we are currently forcing each vanilla key down, so we release it exactly once.
	private boolean forwardHeld;
	private boolean attackHeld;
	private boolean jumpHeld;

	private boolean autoSprint = true;
	private boolean allowMovementInMenus = false;

	private MethodHandle screenGetter;

	@Override
	public void onInitializeClient() {
		loadConfig();
		screenGetter = findScreenGetter();

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, MOD_ID));
		autoWalkKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.autowalk", keyCode("KEY_R", 82), category));
		autoPickKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.autoaction", keyCode("KEY_B", 66), category));
		autoJumpKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.autojump", keyCode("KEY_G", 71), category));

		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
	}

	private void tick(Minecraft client) {
		Options options = client.options;
		if (options == null) {
			return;
		}
		KeyMapping forward = options.keyUp;
		KeyMapping attack = options.keyAttack;
		KeyMapping jump = options.keyJump;
		LocalPlayer player = client.player;

		if (player == null) {
			// Not in a world: drop everything so nothing is stuck down on the next join.
			walking = picking = jumping = false;
			forwardHeld = release(forward, forwardHeld);
			attackHeld = release(attack, attackHeld);
			jumpHeld = release(jump, jumpHeld);
			return;
		}

		while (autoWalkKey.consumeClick()) {
			walking = !walking;
			if (walking) {
				drain(forward); // ignore forward presses from before auto walk started
			}
		}
		while (autoPickKey.consumeClick()) {
			picking = !picking;
		}
		while (autoJumpKey.consumeClick()) {
			jumping = !jumping;
		}

		// Physically tapping forward cancels auto walk, like the original mod.
		if (walking && forward.consumeClick()) {
			walking = false;
			drain(forward);
		}

		// With a menu or inventory open, hold nothing unless allowMovementInMenus is on.
		// The toggles stay on, so everything resumes once the menu closes.
		boolean active = allowMovementInMenus || !isScreenOpen(client);
		boolean walkNow = walking && active;

		// Re-applied every tick because opening a screen releases all key mappings.
		forwardHeld = apply(forward, walkNow, forwardHeld);
		attackHeld = apply(attack, picking && active, attackHeld);
		jumpHeld = apply(jump, jumping && active, jumpHeld);

		if (walkNow && autoSprint && !player.isSprinting()) {
			player.setSprinting(true);
		}
	}

	private static boolean apply(KeyMapping key, boolean want, boolean forced) {
		if (want) {
			key.setDown(true);
			return true;
		}
		return release(key, forced);
	}

	private static boolean release(KeyMapping key, boolean forced) {
		if (forced) {
			key.setDown(false);
		}
		return false;
	}

	private static void drain(KeyMapping key) {
		while (key.consumeClick()) {
			// discard queued presses
		}
	}

	private boolean isScreenOpen(Minecraft client) {
		if (screenGetter == null) {
			return false;
		}
		try {
			return screenGetter.invoke(client) != null;
		} catch (Throwable t) {
			LOGGER.error("Could not read the open screen, menus can no longer be detected", t);
			screenGetter = null;
			return false;
		}
	}

	/** Returns a handle taking a Minecraft and returning the open screen (or null), for 26.1 - 26.3. */
	private static MethodHandle findScreenGetter() {
		MethodHandles.Lookup lookup = MethodHandles.publicLookup();
		try {
			// newer versions (26.3): Minecraft.gui.screen()
			java.lang.reflect.Field gui = Minecraft.class.getField("gui");
			MethodHandle getGui = lookup.unreflectGetter(gui);
			MethodHandle screen = lookup.findVirtual(gui.getType(), "screen",
					MethodType.methodType(Class.forName("net.minecraft.client.gui.screens.Screen")));
			return MethodHandles.filterReturnValue(getGui, screen).asType(MethodType.methodType(Object.class, Minecraft.class));
		} catch (ReflectiveOperationException ignored) {
			// fall through to the older field
		}
		try {
			// older versions (26.1): Minecraft.screen
			return lookup.unreflectGetter(Minecraft.class.getField("screen"))
					.asType(MethodType.methodType(Object.class, Minecraft.class));
		} catch (ReflectiveOperationException e) {
			LOGGER.warn("Could not find the open screen on this Minecraft version; auto keys will keep running in menus", e);
			return null;
		}
	}

	/** Reads InputConstants.KEY_x at runtime: GLFW codes on 26.1/26.2, SDL scancodes on 26.3+. */
	private static int keyCode(String name, int glfwFallback) {
		try {
			return InputConstants.class.getField(name).getInt(null);
		} catch (ReflectiveOperationException e) {
			return glfwFallback;
		}
	}

	private void loadConfig() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("autowalk.properties");
		Properties props = new Properties();
		try {
			if (Files.exists(file)) {
				try (Reader reader = Files.newBufferedReader(file)) {
					props.load(reader);
				}
			}
			autoSprint = Boolean.parseBoolean(props.getProperty("autoSprint", "true"));
			allowMovementInMenus = Boolean.parseBoolean(props.getProperty("allowMovementInMenus", "false"));
			props.remove("autoWalkWithGUI"); // replaced by allowMovementInMenus in v1.1.0

			props.setProperty("autoSprint", Boolean.toString(autoSprint));
			props.setProperty("allowMovementInMenus", Boolean.toString(allowMovementInMenus));
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file)) {
				props.store(writer, "AutoWalk config\n"
						+ " autoSprint: sprint while auto walking\n"
						+ " allowMovementInMenus: keep auto walk, pick and jump running while a menu or inventory is open\n"
						+ "   WARNING: moving while a menu or inventory is open is easily detected by server\n"
						+ "   anti-cheats and can get you kicked or banned. Only enable it where that is allowed.");
			if (allowMovementInMenus) {
				LOGGER.warn("allowMovementInMenus is enabled. Moving in menus is easily detected by server anti-cheats.");
			}
			}
		} catch (Exception e) {
			LOGGER.error("Problem loading config, using defaults", e);
		}
	}
}
