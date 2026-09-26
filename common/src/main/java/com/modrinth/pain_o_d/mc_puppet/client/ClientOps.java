package com.modrinth.pain_o_d.mc_puppet.client;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import com.modrinth.pain_o_d.mc_puppet.core.Args;
import com.modrinth.pain_o_d.mc_puppet.core.GameJson;
import com.modrinth.pain_o_d.mc_puppet.core.Ops;
import com.modrinth.pain_o_d.mc_puppet.core.Reach;
import com.modrinth.pain_o_d.mc_puppet.core.Waiter;
import com.modrinth.pain_o_d.mc_puppet.mixin.HandledScreenAccessor;
import com.modrinth.pain_o_d.mc_puppet.mixin.MerchantScreenAccessor;
import com.modrinth.pain_o_d.mc_puppet.mixin.SliderWidgetAccessor;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import dev.architectury.platform.Platform;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.ParentElement;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.MessageScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CheckboxWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.c2s.play.SelectMerchantTradeC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPreset;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;

/**
 * What the client side answers to.
 *
 * <p>The reason the mod exists. A server can be driven from its console and
 * its logic tested with a fake player; a screen cannot. Whether a button is
 * where it should be, whether a counter restates without the screen closing,
 * whether a slot holds what the player was shown, is only knowable in a
 * client, and until now only by a person looking at it.
 *
 * <p>Three kinds of operation: <b>seeing</b> (the screen as data, the player,
 * chat, a screenshot for what data cannot say), <b>doing</b> (clicks, keys,
 * commands, using an entity or a block, as the player would, through the same
 * code paths), and <b>getting there</b> (making, opening and leaving a world,
 * window size and GUI scale, waiting).
 *
 * <p>Everything here runs on the render thread.
 */
public final class ClientOps {

    private ClientOps() {
    }

    public static Ops create(MinecraftClient client, Waiter waiter, ChatLog chat) {
        Ops ops = new Ops("client", client::execute, waiter);

        // ---- seeing ---------------------------------------------------------

        ops.now("info", "{}", "Version, mods, window, whether a world is loaded.", args -> info(client));

        ops.now("screen", "{slots?: true, empty_slots?: false, widgets?: true}",
                "The open screen as data: class, title, size, widgets (index, kind, text, x, y, w, h, text_w, "
                        + "visible, active), and for a container its slots, cursor stack and a merchant's offers.",
                args -> screen(client, args));

        ops.now("player", "{inventory?: false}", "Position, dimension, game mode, health, held item.",
                args -> player(client, args));

        ops.now("count", "{item}", "How many of one item the player holds.",
                args -> new JsonPrimitive(GameJson.countOf(requirePlayer(client).getInventory(),
                        Args.string(args, "item"))));

        ops.now("chat", "{since?: seq, contains?: text, limit?: 50}",
                "Chat and system messages received, each with a sequence number; commands answer here.",
                args -> chat.read(Args.number(args, "since", 0), Args.string(args, "contains", null),
                        Args.integer(args, "limit", 50)));

        ops.now("entities", "{type?, radius?: 16, limit?: 20}", "Entities near the player, nearest first.",
                args -> entities(client, args));

        ops.add("watch", "{type?, radius?: 32, ticks?: 100, jump?: 1.0, turn?: 45, drawn_only?: true, ai?: true|false}",
                "Watches the entities near the player every tick for so many ticks (at most 1200) and says how they "
                        + "moved: appeared, disappeared, blinks (lived five ticks or fewer), the largest step a tick and "
                        + "jumps (steps over \"jump\" blocks), the sharpest turn and turns over \"turn\" degrees, those "
                        + "moving with still legs (sliding, sliding_share), floating (held up over air), buried (inside "
                        + "a block), overlaps (pairs closer than their width), and the worst of each with where and when. "
                        + "What a screenshot shows one frame of, as numbers a test can hold. With \"drawn_only\" (the "
                        + "default) only what the renderer would draw counts - a mod may hide an entity behind a stand-in. "
                        + "Adds the frames while it watched: fps, frame_ms_mean, frame_ms_p95, frame_ms_max, stalls_over_50ms. "
                        + "\"ai\": false watches only mobs with no brain (a mod's placed bodies and pictures), true only the rest.",
                args -> {
                    requirePlayer(client);
                    String type = Args.string(args, "type", null);
                    double radius = args.has("radius") ? Args.decimal(args, "radius") : 32;
                    boolean drawnOnly = Args.flag(args, "drawn_only", true);
                    net.minecraft.client.render.Frustum everywhere = new net.minecraft.client.render.Frustum(new org.joml.Matrix4f(), new org.joml.Matrix4f()) {
                        @Override
                        public boolean isVisible(net.minecraft.util.math.Box box) {
                            return true;   // what the renderer would draw anywhere round the player, not only in view
                        }
                    };
                    com.modrinth.pain_o_d.mc_puppet.core.Watch watch = new com.modrinth.pain_o_d.mc_puppet.core.Watch(() -> {
                        java.util.List<net.minecraft.entity.Entity> found = new java.util.ArrayList<>();
                        if (client.world == null || client.player == null) {
                            return found;
                        }
                        for (net.minecraft.entity.Entity entity : client.world.getEntities()) {
                            if (entity != client.player && com.modrinth.pain_o_d.mc_puppet.core.Watch.ofType(entity, type)
                                    && com.modrinth.pain_o_d.mc_puppet.core.Watch.ofAi(entity, args)
                                    && entity.squaredDistanceTo(client.player) <= radius * radius) {
                                net.minecraft.util.math.Vec3d eye = client.gameRenderer.getCamera().getPos();
                                if (!drawnOnly || client.getEntityRenderDispatcher().shouldRender(entity, everywhere, eye.x, eye.y, eye.z)) {
                                    found.add(entity);
                                }
                            }
                        }
                        return found;
                    }, args);
                    long framesFrom = FrameClock.frames();
                    long startedNanos = System.nanoTime();
                    return waiter.until("the watch to end", watch.ticks() * 100L + 10_000, () -> {
                        JsonElement seen = watch.tick();
                        if (seen instanceof JsonObject summary) {
                            FrameClock.since(framesFrom, startedNanos).entrySet().forEach(e -> summary.add(e.getKey(), e.getValue()));
                        }
                        return seen;
                    });
                });

        ops.add("screenshot", "{name?}",
                "Saves the last frame to screenshots/ and returns {path}. For what data cannot say: overlap, "
                        + "clipping, colour.",
                args -> screenshot(client, args));

        // ---- doing ----------------------------------------------------------

        ops.now("click_widget", "{index?: n, text?: substring, button?: 0, modifiers?, direct?: false}",
                "Clicks a widget at its centre, by index from \"screen\" or by its text, through the game's "
                        + "mouse handler. \"direct\" calls the screen's own method instead, skipping loader events.",
                args -> clickWidget(client, args));

        ops.now("click_at", "{x, y} | {slot: n} | {widget: index|text}, button?: 0, "
                        + "modifiers?: [shift|control|alt], direct?: false",
                "Clicks at scaled GUI coordinates, or on a slot or a widget, through the game's mouse handler: "
                        + "{slot: 2, modifiers: [shift]} is the shift-click a player makes. With no screen open it "
                        + "is a click in the world: the attack or use key.",
                args -> {
                    double[] at = pointOf(client, args);
                    double x = at[0];
                    double y = at[1];
                    int button = Input.buttonOf(args);
                    if (Args.flag(args, "direct", false)) {
                        Screen screen = requireScreen(client);
                        boolean handled = screen.mouseClicked(x, y, button);
                        screen.mouseReleased(x, y, button);
                        return new JsonPrimitive(handled);
                    }
                    return Input.withModifiers(args, () -> {
                        Input.click(client, x, y, button);
                        return JsonNull.INSTANCE;
                    });
                });

        ops.add("hover", "{x, y} | {widget: index|text} | {slot: n}",
                "Moves the cursor there and waits a frame, so that what hovering shows is on screen: a tooltip, "
                        + "a highlight. Follow with tooltip, frame or screenshot.",
                args -> {
                    double[] at = pointOf(client, args);
                    Input.moveTo(client, at[0], at[1]);
                    return Input.overTicks(waiter, "a frame", List.of(() -> { }, () -> { }), () -> {
                        JsonObject json = new JsonObject();
                        json.addProperty("x", at[0]);
                        json.addProperty("y", at[1]);
                        return json;
                    });
                });

        ops.add("drag", "{from: {x,y}|{slot}|{widget}, to: [{x,y}|{slot}|{widget}, …] | {…}, button?: 0, "
                        + "modifiers?}",
                "Presses at \"from\", moves through each of \"to\" a tick apart, and releases at the last. With "
                        + "a stack on the cursor, dragging over slots spreads it, as it does for a player.",
                args -> drag(client, waiter, args));

        ops.now("scroll", "{amount, x?, y?, widget?, slot?}",
                "Turns the wheel, positive up, over a point, a widget or a slot; by default the middle of the "
                        + "screen. A merchant's list of trades scrolls this way.",
                args -> {
                    double[] at = args.has("x") || args.has("widget") || args.has("slot")
                            ? pointOf(client, args)
                            : new double[] {client.getWindow().getScaledWidth() / 2.0,
                                    client.getWindow().getScaledHeight() / 2.0};
                    Input.scroll(client, at[0], at[1], Args.decimal(args, "amount"));
                    return JsonNull.INSTANCE;
                });

        ops.now("click_slot", "{slot, button?: 0, action?: PICKUP|QUICK_MOVE|SWAP|CLONE|THROW|PICKUP_ALL}",
                "Clicks a container slot as the player would; QUICK_MOVE is shift-click.",
                args -> clickSlot(client, args));

        ops.now("select_trade", "{index}", "Selects a merchant's offer, as clicking it in the list does.",
                args -> selectTrade(client, args));

        ops.now("key", "{key: name|letter|digit|code, action?: tap|press|release, modifiers?: [shift|control|alt]}",
                "A key, through the game's keyboard handler: a screen hears it, and with no screen open the key "
                        + "bindings do (e opens the inventory, f5 changes the view). press holds it until release, "
                        + "and the game believes it is down meanwhile: hold shift, then click. Names: escape, "
                        + "enter, tab, backspace, delete, arrows, home, end, space, shift, control, alt, f1-f25.",
                args -> {
                    int code = VirtualKeys.code(Args.string(args, "key"));
                    String action = Args.string(args, "action", "tap");
                    return Input.withModifiers(args, () -> {
                        if (!action.equals("release")) {
                            Input.key(client, code, true);
                        }
                        if (!action.equals("press")) {
                            Input.key(client, code, false);
                        }
                        return JsonNull.INSTANCE;
                    });
                });

        ops.now("set_text", "{widget: index|text, text}",
                "Focuses a text field and sets what it holds, as if typed over: its listeners hear it. For a "
                        + "field among several; \"type\" goes to whichever has focus.",
                args -> {
                    Screen screen = requireScreen(client);
                    if (!(widgetBy(screen, Args.string(args, "widget")) instanceof TextFieldWidget field)) {
                        throw new Ops.Refused("that widget is not a text field");
                    }
                    screen.setFocused(field);
                    field.setText(Args.string(args, "text"));
                    return new JsonPrimitive(field.getText());
                });

        ops.now("release_keys", "{}", "Lets go of every key a test is holding.", args -> {
            VirtualKeys.releaseAll();
            return JsonNull.INSTANCE;
        });

        ops.now("type", "{text}", "Types text, through the game's keyboard handler, into whatever has focus.",
                args -> {
                    Input.type(client, Args.string(args, "text"));
                    return JsonNull.INSTANCE;
                });

        ops.now("close_screen", "{}", "Closes the open screen, as escape does for a container.", args -> {
            if (client.player != null && client.currentScreen instanceof HandledScreen<?>) {
                client.player.closeHandledScreen();
            } else {
                client.setScreen(null);
            }
            return JsonNull.INSTANCE;
        });

        ops.now("command", "{command}", "Sends a command as the player, without the slash. Read the answer with \"chat\".",
                args -> {
                    requirePlayer(client).networkHandler.sendChatCommand(stripSlash(Args.string(args, "command")));
                    return new JsonPrimitive(chat.sequence());
                });

        ops.now("say", "{message}", "Sends a chat message as the player.", args -> {
            requirePlayer(client).networkHandler.sendChatMessage(Args.string(args, "message"));
            return JsonNull.INSTANCE;
        });

        ops.now("use_entity", "{uuid? | type?, radius?: 6}",
                "Looks at and right-clicks an entity: by uuid, or the nearest of a type. Opens a villager.",
                args -> useEntity(client, args));

        ops.now("use_block", "{x, y, z, side?: up}", "Looks at and right-clicks a block.", args -> {
            ClientPlayerEntity player = requirePlayer(client);
            BlockPos pos = new BlockPos(Args.integer(args, "x"), Args.integer(args, "y"), Args.integer(args, "z"));
            Direction side = Direction.byName(Args.string(args, "side", "up"));
            Vec3d centre = Vec3d.ofCenter(pos);
            player.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, centre);
            return new JsonPrimitive(client.interactionManager.interactBlock(player, Hand.MAIN_HAND,
                    new BlockHitResult(centre, side == null ? Direction.UP : side, pos, false)).toString());
        });

        ops.now("use_item", "{}", "Right-clicks with the held item.", args -> new JsonPrimitive(
                client.interactionManager.interactItem(requirePlayer(client), Hand.MAIN_HAND).toString()));

        ops.now("hotbar", "{slot: 0-8}", "Selects a hotbar slot.", args -> {
            requirePlayer(client).getInventory().selectedSlot = Math.max(0, Math.min(8, Args.integer(args, "slot")));
            return JsonNull.INSTANCE;
        });

        // ---- getting there ----------------------------------------------------

        ops.now("worlds", "{}", "Saved worlds, by folder name.", args -> worlds(client));

        ops.now("create_world",
                "{name, mode?: creative|survival, flat?: false, seed?: n, cheats?: true, structures?: true, "
                        + "difficulty?: normal}",
                "Makes a world and starts it. Follow with wait {for: world}.",
                args -> createWorld(client, args));

        ops.now("open_world", "{name}", "Starts a saved world. Follow with wait {for: world}.", args -> {
            requireLoaded(client);
            String name = worldName(args);
            if (!Files.isDirectory(client.getLevelStorage().getSavesDirectory().resolve(name))) {
                throw new Ops.Refused("no saved world in a folder called " + name);
            }
            if (client.world != null) {
                throw new Ops.Refused("a world is loaded; leave_world first");
            }
            com.modrinth.pain_o_d.mc_puppet.compat.ClientCompat.openWorld(client, name);
            return JsonNull.INSTANCE;
        });

        ops.now("join_server", "{address: localhost[:port]}",
                "Joins a server on this machine, or one whose port was brought here (ssh -L). Follow with "
                        + "wait {for: world}, which says why if the server turns the player away.",
                args -> joinServer(client, args));

        ops.now("leave_world", "{}", "Saves and leaves to the title screen. Follow with wait {for: no_world}.",
                args -> {
                    if (client.world == null) {
                        return new JsonPrimitive(false);
                    }
                    boolean single = client.isInSingleplayer();
                    client.world.disconnect();
                    if (single) {
                        client.disconnect(new MessageScreen(Text.translatable("menu.savingLevel")));
                    } else {
                        client.disconnect();
                    }
                    client.setScreen(new TitleScreen());
                    return new JsonPrimitive(true);
                });

        ops.now("quit", "{}", "Closes the game, saving the world first as the quit button does. For CI.", args -> {
            client.scheduleStop();
            return JsonNull.INSTANCE;
        });

        ops.now("window", "{width?, height?, gui_scale?: 0-4}",
                "Resizes the window and sets the GUI scale (0 is auto). Returns the scaled size.",
                args -> window(client, args));

        ops.add("wait",
                "{for: screen|no_screen|world|no_world|loaded|chat|ticks, value?: text|n, since?: seq, timeout_ms?: 30000}",
                "Waits, from the game's tick: for a screen (value: part of its class or title), for none, for a "
                        + "world to be playable, for none, for a chat line containing value, or for so many ticks.",
                args -> waitFor(client, waiter, chat, args));

        ops.now("record_start", "{}",
                "Starts writing down what the player does in screens, in a scenario's words: widgets by their "
                        + "text, slots by number with the modifiers held, screens by a name that survives a "
                        + "release build, and the entity or block used to open one.",
                args -> {
                    Recorder.start();
                    return JsonNull.INSTANCE;
                });

        ops.now("record_stop", "{name?: recorded}",
                "Stops, and returns what was done as a scenario: steps without expectations, which are the "
                        + "author's to add.",
                args -> {
                    if (!Recorder.recording()) {
                        throw new Ops.Refused("nothing is being recorded; record_start first");
                    }
                    return Recorder.stop(Args.string(args, "name", "recorded"));
                });

        ops.now("record_status", "{}", "Whether a recording is running, and how many steps it has.", args -> {
            JsonObject json = new JsonObject();
            json.addProperty("recording", Recorder.recording());
            json.addProperty("steps", Recorder.count());
            return json;
        });

        Sight.register(ops, client, waiter);
        Body.register(ops, client, waiter);
        return ops;
    }

    // ---- seeing ---------------------------------------------------------------

    private static JsonElement info(MinecraftClient client) {
        JsonObject info = new JsonObject();
        info.addProperty("side", "client");
        info.addProperty("protocol", com.modrinth.pain_o_d.mc_puppet.core.Protocol.VERSION);
        info.addProperty("mod_version", dev.architectury.platform.Platform.getMod("mc_puppet").getVersion());
        info.addProperty("development", com.modrinth.pain_o_d.mc_puppet.McPuppet.development());
        info.addProperty("minecraft", net.minecraft.SharedConstants.getGameVersion().getName());
        info.addProperty("loader", dev.architectury.platform.Platform.isFabric() ? "fabric" : com.modrinth.pain_o_d.mc_puppet.compat.Compat.OTHER_LOADER);
        info.addProperty("username", client.getSession().getUsername());
        info.addProperty("in_world", client.world != null && client.player != null);
        info.addProperty("singleplayer", client.isInSingleplayer());
        // True on a server that is not on this machine, where nearly everything is refused: see core/Reach.
        info.addProperty("elsewhere", PuppetClient.elsewhere(client));
        info.addProperty("screen", client.currentScreen == null ? null : client.currentScreen.getClass().getName());
        info.add("window", windowJson(client));
        JsonArray mods = new JsonArray();
        for (var mod : Platform.getMods()) {
            mods.add(mod.getModId() + " " + mod.getVersion());
        }
        info.add("mods", mods);
        return info;
    }

    private static JsonObject windowJson(MinecraftClient client) {
        JsonObject window = new JsonObject();
        window.addProperty("width", client.getWindow().getWidth());
        window.addProperty("height", client.getWindow().getHeight());
        window.addProperty("scaled_width", client.getWindow().getScaledWidth());
        window.addProperty("scaled_height", client.getWindow().getScaledHeight());
        window.addProperty("gui_scale", client.getWindow().getScaleFactor());
        return window;
    }

    /** Every clickable widget of a screen, containers flattened, in the order the screen holds them. */
    static List<ClickableWidget> widgetsOf(Screen screen) {
        List<ClickableWidget> found = new ArrayList<>();
        collect(screen.children(), found, 0);
        return found;
    }

    private static void collect(List<? extends Element> elements, List<ClickableWidget> into, int depth) {
        for (Element element : elements) {
            if (element instanceof ClickableWidget widget) {
                into.add(widget);
            }
            if (element instanceof ParentElement parent && depth < 4) {
                collect(parent.children(), into, depth + 1);
            }
        }
    }

    private static String kindOf(ClickableWidget widget) {
        if (widget instanceof TextFieldWidget) {
            return "text_field";
        }
        if (widget instanceof CheckboxWidget) {
            return "checkbox";
        }
        if (widget instanceof SliderWidget) {
            return "slider";
        }
        if (widget instanceof CyclingButtonWidget) {
            return "cycling_button";
        }
        if (widget instanceof ButtonWidget) {
            return "button";
        }
        return "widget";
    }

    private static JsonElement screen(MinecraftClient client, JsonObject args) {
        Screen screen = client.currentScreen;
        if (screen == null) {
            return JsonNull.INSTANCE;
        }
        JsonObject json = new JsonObject();
        json.addProperty("class", screen.getClass().getName());
        json.addProperty("title", screen.getTitle().getString());
        String titleKey = titleKeyOf(screen);
        if (titleKey != null) {
            json.addProperty("title_key", titleKey);
        }
        String handlerType = handlerTypeOf(screen);
        if (handlerType != null) {
            json.addProperty("handler_type", handlerType);
        }
        json.addProperty("width", screen.width);
        json.addProperty("height", screen.height);

        if (Args.flag(args, "widgets", true)) {
            JsonArray widgets = new JsonArray();
            int index = 0;
            for (ClickableWidget widget : widgetsOf(screen)) {
                JsonObject one = new JsonObject();
                one.addProperty("index", index++);
                one.addProperty("kind", kindOf(widget));
                one.addProperty("text", widget instanceof TextFieldWidget field
                        ? field.getText() : widget.getMessage().getString());
                one.addProperty("x", widget.getX());
                one.addProperty("y", widget.getY());
                one.addProperty("w", widget.getWidth());
                one.addProperty("h", widget.getHeight());
                // How wide its text is drawn. Wider than the widget is a label
                // the player sees cut off or scrolling, which no other field
                // here would show: the first screenshot of a real mod had one.
                one.addProperty("text_w", client.textRenderer.getWidth(widget.getMessage()));
                if (!widget.visible) {
                    one.addProperty("visible", false);
                }
                if (!widget.active) {
                    one.addProperty("active", false);
                }
                if (widget.isFocused()) {
                    one.addProperty("focused", true);
                }
                // What a widget is set to, which its text only sometimes says.
                if (widget instanceof CheckboxWidget checkbox) {
                    one.addProperty("checked", checkbox.isChecked());
                } else if (widget instanceof SliderWidget) {
                    one.addProperty("value", Math.round(((SliderWidgetAccessor) widget).mc_puppet$value() * 1000)
                            / 1000.0);
                } else if (widget instanceof CyclingButtonWidget<?> cycling) {
                    one.addProperty("value", String.valueOf(cycling.getValue()));
                } else if (widget instanceof TextFieldWidget field) {
                    one.addProperty("label", widget.getMessage().getString());
                    one.addProperty("editable", field.isActive());
                }
                if (widget.getTooltip() != null) {
                    one.addProperty("has_tooltip", true);
                }
                widgets.add(one);
            }
            json.add("widgets", widgets);
        }

        if (screen instanceof HandledScreen<?> handled) {
            HandledScreenAccessor bounds = (HandledScreenAccessor) handled;
            JsonObject panel = new JsonObject();
            panel.addProperty("x", bounds.mc_puppet$x());
            panel.addProperty("y", bounds.mc_puppet$y());
            panel.addProperty("w", bounds.mc_puppet$backgroundWidth());
            panel.addProperty("h", bounds.mc_puppet$backgroundHeight());
            json.add("panel", panel);

            ScreenHandler handler = handled.getScreenHandler();
            json.addProperty("sync_id", handler.syncId);
            json.add("cursor", GameJson.stack(handler.getCursorStack()));
            if (Args.flag(args, "slots", true)) {
                boolean withEmpty = Args.flag(args, "empty_slots", false);
                JsonArray slots = new JsonArray();
                for (Slot slot : handler.slots) {
                    if (!slot.hasStack() && !withEmpty) {
                        continue;
                    }
                    JsonObject one = new JsonObject();
                    one.addProperty("slot", slot.id);
                    one.addProperty("x", bounds.mc_puppet$x() + slot.x);
                    one.addProperty("y", bounds.mc_puppet$y() + slot.y);
                    if (client.player != null && slot.inventory == client.player.getInventory()) {
                        one.addProperty("player", true);
                    }
                    one.add("stack", GameJson.stack(slot.getStack()));
                    slots.add(one);
                }
                json.add("slots", slots);
                json.addProperty("slot_count", handler.slots.size());
            }
            if (handler instanceof MerchantScreenHandler merchant) {
                json.add("offers", GameJson.offers(merchant.getRecipes()));
                if (screen instanceof MerchantScreen) {
                    json.addProperty("selected_offer", ((MerchantScreenAccessor) screen).mc_puppet$selectedIndex());
                }
            }
        }
        return json;
    }

    private static JsonElement player(MinecraftClient client, JsonObject args) throws Ops.Refused {
        ClientPlayerEntity player = requirePlayer(client);
        JsonObject json = new JsonObject();
        json.addProperty("name", player.getGameProfile().getName());
        json.addProperty("uuid", player.getUuidAsString());
        json.add("pos", GameJson.pos(player.getPos()));
        json.addProperty("yaw", Math.round(player.getYaw()));
        json.addProperty("pitch", Math.round(player.getPitch()));
        json.addProperty("dimension", player.getWorld().getRegistryKey().getValue().toString());
        json.addProperty("game_mode", client.interactionManager.getCurrentGameMode().getName());
        json.addProperty("health", player.getHealth());
        json.addProperty("food", player.getHungerManager().getFoodLevel());
        json.add("held", GameJson.stack(player.getMainHandStack()));
        if (Args.flag(args, "inventory", false)) {
            json.add("inventory", GameJson.inventory(player.getInventory()));
        }
        return json;
    }

    private static JsonElement entities(MinecraftClient client, JsonObject args) throws Ops.Refused {
        ClientPlayerEntity player = requirePlayer(client);
        String type = Args.string(args, "type", null);
        double radius = args.has("radius") ? Args.decimal(args, "radius") : 16;
        int limit = Math.max(1, Math.min(200, Args.integer(args, "limit", 20)));
        List<Entity> found = new ArrayList<>();
        for (Entity entity : client.world.getEntities()) {
            if (entity != player && entity.isAlive() && entity.squaredDistanceTo(player) <= radius * radius
                    && (type == null || Registries.ENTITY_TYPE.getId(entity.getType()).toString().equals(type))) {
                found.add(entity);
            }
        }
        found.sort((a, b) -> Double.compare(a.squaredDistanceTo(player), b.squaredDistanceTo(player)));
        JsonArray list = new JsonArray();
        for (Entity entity : found.subList(0, Math.min(limit, found.size()))) {
            JsonObject one = GameJson.entity(entity, false);
            one.addProperty("distance", Math.round(entity.distanceTo(player) * 10.0) / 10.0);
            list.add(one);
        }
        return list;
    }

    private static CompletableFuture<JsonElement> screenshot(MinecraftClient client, JsonObject args) {
        String name = Args.string(args, "name", "puppet-" + System.currentTimeMillis());
        String fileName = name.replaceAll("[^A-Za-z0-9._-]", "_") + (name.endsWith(".png") ? "" : ".png");
        File gameDir = client.runDirectory;
        Path path = gameDir.toPath().toAbsolutePath().normalize().resolve("screenshots").resolve(fileName);
        CompletableFuture<JsonElement> done = new CompletableFuture<>();
        ScreenshotRecorder.saveScreenshot(gameDir, fileName, client.getFramebuffer(), said -> {
            if (Files.exists(path)) {
                JsonObject json = new JsonObject();
                json.addProperty("path", path.toAbsolutePath().toString());
                json.add("window", windowJson(client));
                done.complete(json);
            } else {
                done.completeExceptionally(new Ops.Refused("the screenshot was not saved: " + said.getString()));
            }
        });
        return done;
    }

    // ---- doing ------------------------------------------------------------------

    private static JsonElement clickWidget(MinecraftClient client, JsonObject args) throws Ops.Refused {
        Screen screen = requireScreen(client);
        List<ClickableWidget> widgets = widgetsOf(screen);
        ClickableWidget target = null;
        if (args.has("index")) {
            int index = Args.integer(args, "index");
            if (index < 0 || index >= widgets.size()) {
                throw new Ops.Refused("the screen has " + widgets.size() + " widgets; there is no index " + index);
            }
            target = widgets.get(index);
        } else {
            String text = Args.string(args, "text").toLowerCase(Locale.ROOT);
            for (ClickableWidget widget : widgets) {
                if (widget.visible && widget.getMessage().getString().toLowerCase(Locale.ROOT).contains(text)) {
                    target = widget;
                    break;
                }
            }
            if (target == null) {
                throw new Ops.Refused("no visible widget says \"" + Args.string(args, "text") + "\"");
            }
        }
        if (!target.visible || !target.active) {
            throw new Ops.Refused("that widget is " + (target.visible ? "inactive" : "hidden")
                    + ": \"" + target.getMessage().getString() + "\"");
        }
        double x = target.getX() + target.getWidth() / 2.0;
        double y = target.getY() + target.getHeight() / 2.0;
        int button = Input.buttonOf(args);
        JsonObject json = new JsonObject();
        json.addProperty("clicked", target.getMessage().getString());
        if (Args.flag(args, "direct", false)) {
            json.addProperty("handled", screen.mouseClicked(x, y, button));
            screen.mouseReleased(x, y, button);
            return json;
        }
        // Through the mouse handler, which is where the loaders raise the
        // screen events a mod may be listening to instead of overriding.
        Input.withModifiers(args, () -> {
            Input.click(client, x, y, button);
            return null;
        });
        return json;
    }

    private static JsonElement clickSlot(MinecraftClient client, JsonObject args) throws Ops.Refused {
        ClientPlayerEntity player = requirePlayer(client);
        if (!(client.currentScreen instanceof HandledScreen<?> handled)) {
            throw new Ops.Refused("no container screen is open");
        }
        ScreenHandler handler = handled.getScreenHandler();
        int slot = Args.integer(args, "slot");
        if (slot != -999 && (slot < 0 || slot >= handler.slots.size())) {
            throw new Ops.Refused("the container has " + handler.slots.size() + " slots; there is no slot " + slot);
        }
        SlotActionType action;
        try {
            action = SlotActionType.valueOf(Args.string(args, "action", "PICKUP").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new Ops.Refused("\"action\" is one of PICKUP, QUICK_MOVE, SWAP, CLONE, THROW, QUICK_CRAFT, PICKUP_ALL");
        }
        client.interactionManager.clickSlot(handler.syncId, slot, Args.integer(args, "button", 0), action, player);
        JsonObject json = new JsonObject();
        json.add("cursor", GameJson.stack(handler.getCursorStack()));
        if (slot >= 0) {
            json.add("slot", GameJson.stack(handler.getSlot(slot).getStack()));
        }
        return json;
    }

    private static JsonElement selectTrade(MinecraftClient client, JsonObject args) throws Ops.Refused {
        if (!(client.currentScreen instanceof MerchantScreen screen)) {
            throw new Ops.Refused("no trading screen is open");
        }
        MerchantScreenHandler handler = screen.getScreenHandler();
        int index = Args.integer(args, "index");
        if (index < 0 || index >= handler.getRecipes().size()) {
            throw new Ops.Refused("the merchant has " + handler.getRecipes().size() + " offers; there is no " + index);
        }
        // What MerchantScreen does when an offer in its list is clicked.
        ((MerchantScreenAccessor) screen).mc_puppet$setSelectedIndex(index);
        handler.setRecipeIndex(index);
        handler.switchTo(index);
        // Through the connection, not the handler: NeoForge replaces the handler's
        // sendPacket with one of its own, and a call compiled against vanilla's is a
        // NoSuchMethodError there, which the first NeoForge run of the scenario found.
        client.getNetworkHandler().getConnection().send(new SelectMerchantTradeC2SPacket(index));
        return GameJson.offers(handler.getRecipes()).get(index);
    }

    private static JsonElement useEntity(MinecraftClient client, JsonObject args) throws Ops.Refused {
        ClientPlayerEntity player = requirePlayer(client);
        Entity target = null;
        if (args.has("uuid")) {
            UUID uuid;
            try {
                uuid = UUID.fromString(Args.string(args, "uuid"));
            } catch (IllegalArgumentException notAUuid) {
                throw new Ops.Refused("\"uuid\" is not a uuid");
            }
            for (Entity entity : client.world.getEntities()) {
                if (entity.getUuid().equals(uuid) && entity.isAlive()) {
                    target = entity;
                }
            }
        } else {
            String type = Args.string(args, "type");
            double radius = args.has("radius") ? Args.decimal(args, "radius") : 6;
            for (Entity entity : client.world.getEntities()) {
                // Alive: a villager killed a moment ago is still there for a
                // second, falling over, and is as near as its replacement.
                if (entity != player && entity.isAlive() && entity.squaredDistanceTo(player) <= radius * radius
                        && Registries.ENTITY_TYPE.getId(entity.getType()).toString().equals(type)
                        && (target == null || entity.squaredDistanceTo(player) < target.squaredDistanceTo(player))) {
                    target = entity;
                }
            }
        }
        if (target == null) {
            throw new Ops.Refused("no such entity in reach; the server refuses one further than a few blocks");
        }
        player.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, target.getEyePos());
        JsonObject json = GameJson.entity(target, false);
        json.addProperty("result", client.interactionManager.interactEntity(player, target, Hand.MAIN_HAND).toString());
        return json;
    }

    // ---- getting there ------------------------------------------------------------

    private static JsonElement worlds(MinecraftClient client) throws java.io.IOException {
        JsonArray names = new JsonArray();
        Path saves = client.getLevelStorage().getSavesDirectory();
        if (Files.isDirectory(saves)) {
            try (Stream<Path> folders = Files.list(saves)) {
                folders.filter(folder -> Files.exists(folder.resolve("level.dat")))
                        .map(folder -> folder.getFileName().toString()).sorted().forEach(names::add);
            }
        }
        return names;
    }

    /**
     * A world is not opened from inside a resource reload: the client would
     * wait for a server that is waiting for it. F3+T in the middle of a test
     * is enough to get here.
     */
    private static void requireLoaded(MinecraftClient client) throws Ops.Refused {
        if (client.getOverlay() != null) {
            throw new Ops.Refused("the game is loading its resources; wait {for: loaded} first");
        }
    }

    /**
     * A world's name is a folder's name under saves/, and whoever is connected chose it. One
     * folder, by name: nothing that walks out of saves/ or means something to a file system.
     * Any language's letters are fine; people call their worlds what they like.
     */
    static String worldName(JsonObject args) throws Ops.Refused {
        String name = Args.string(args, "name");
        boolean walks = name.isBlank() || name.equals(".") || name.contains("..") || name.endsWith(".")
                || name.endsWith(" ") || name.startsWith(" ");
        for (int index = 0; index < name.length() && !walks; index++) {
            char letter = name.charAt(index);
            walks = Character.isISOControl(letter) || "/\\:*?\"<>|".indexOf(letter) >= 0;
        }
        if (walks || name.length() > 100) {
            throw new Ops.Refused("a world's name is one folder's name under saves/: no slashes, no \"..\", "
                    + "none of : * ? \" < > |, and no more than a hundred characters");
        }
        return name;
    }

    private static JsonElement createWorld(MinecraftClient client, JsonObject args) throws Ops.Refused {
        requireLoaded(client);
        if (client.world != null) {
            throw new Ops.Refused("a world is loaded; leave_world first");
        }
        String name = worldName(args);
        if (Files.exists(client.getLevelStorage().getSavesDirectory().resolve(name))) {
            throw new Ops.Refused("a world folder called " + name + " exists; open_world it, or pick another name");
        }
        GameMode mode = GameMode.byName(Args.string(args, "mode", "creative"), GameMode.CREATIVE);
        Difficulty difficulty = Difficulty.byName(Args.string(args, "difficulty", "normal"));
        boolean flat = Args.flag(args, "flat", false);
        long seed = args.has("seed") ? Args.number(args, "seed", 0) : GeneratorOptions.getRandomSeed();

        LevelInfo level = new LevelInfo(name, mode, false, difficulty == null ? Difficulty.NORMAL : difficulty,
                Args.flag(args, "cheats", true), new GameRules(), DataConfiguration.SAFE_MODE);
        GeneratorOptions generator = new GeneratorOptions(seed, Args.flag(args, "structures", true), false);
        com.modrinth.pain_o_d.mc_puppet.compat.ClientCompat.createWorld(client, name, level, generator,
                registries -> {
                    WorldPreset preset = registries.get(RegistryKeys.WORLD_PRESET)
                            .entryOf(flat ? WorldPresets.FLAT : WorldPresets.DEFAULT).value();
                    return preset.createDimensionsRegistryHolder();
                });
        JsonObject json = new JsonObject();
        json.addProperty("name", name);
        // As text: a seed is sixty-four bits and a JSON number, to most readers, is fifty-three.
        json.addProperty("seed", Long.toString(seed));
        return json;
    }

    /**
     * To a server on this machine and to no other. Anywhere else the bridge would go deaf the
     * moment the player arrived (see {@link Reach}), and a program that can send a player's game
     * and account to an address of its choosing is nothing a test needs.
     */
    private static JsonElement joinServer(MinecraftClient client, JsonObject args) throws Ops.Refused {
        String address = Args.string(args, "address").trim();
        if (!ServerAddress.isValid(address)) {
            throw new Ops.Refused("\"" + address + "\" is not an address; localhost:25565 is one");
        }
        ServerAddress parsed = ServerAddress.parse(address);
        String refused = Reach.refusalToJoin(parsed.getAddress());
        if (refused != null) {
            throw new Ops.Refused(refused);
        }
        requireLoaded(client);
        if (client.world != null) {
            throw new Ops.Refused("a world is loaded; leave_world first");
        }
        com.modrinth.pain_o_d.mc_puppet.compat.ClientCompat.joinServer(client, parsed, address);
        JsonObject json = new JsonObject();
        json.addProperty("host", parsed.getAddress());
        json.addProperty("port", parsed.getPort());
        return json;
    }

    private static JsonElement window(MinecraftClient client, JsonObject args) throws Ops.Refused {
        if (args.has("width") || args.has("height")) {
            client.getWindow().setWindowedSize(
                    Math.max(320, Args.integer(args, "width", client.getWindow().getWidth())),
                    Math.max(240, Args.integer(args, "height", client.getWindow().getHeight())));
        }
        if (args.has("gui_scale")) {
            client.options.getGuiScale().setValue(Math.max(0, Math.min(8, Args.integer(args, "gui_scale"))));
        }
        client.onResolutionChanged();
        return windowJson(client);
    }

    private static CompletableFuture<JsonElement> waitFor(MinecraftClient client, Waiter waiter, ChatLog chat,
                                                          JsonObject args) throws Ops.Refused {
        String what = Args.string(args, "for");
        long timeout = Args.timeout(args);
        String value = Args.string(args, "value", "");
        String wanted = value.toLowerCase(Locale.ROOT);
        switch (what) {
            case "screen":
                return waiter.until("a screen" + (value.isEmpty() ? "" : " like \"" + value + "\""), timeout, () -> {
                    Screen screen = client.currentScreen;
                    if (screen == null) {
                        return null;
                    }
                    boolean matches = wanted.isEmpty() || screenIs(screen, wanted);
                    return matches ? new JsonPrimitive(screen.getClass().getName()) : null;
                });
            case "no_screen":
                return waiter.until("no screen", timeout,
                        () -> client.currentScreen == null ? new JsonPrimitive(true) : null);
            case "world":
                // Playable: a player in a world, and no loading screen over it.
                return waiter.until("a world to be playable", timeout, () -> {
                    // A server that said no has said why, and no amount of waiting turns that into a world.
                    if (client.world == null && client.currentScreen instanceof DisconnectedScreen turnedAway) {
                        throw new java.util.concurrent.CompletionException(new Ops.Refused(
                                "there will be no world: " + turnedAway.getNarratedTitle().getString()));
                    }
                    return client.world != null && client.player != null && client.currentScreen == null
                            ? new JsonPrimitive(client.player.getGameProfile().getName()) : null;
                });
            case "loaded":
                // After a resource reload (F3+T, a resource pack), until the splash is gone.
                return waiter.until("the game to finish loading its resources", timeout,
                        () -> client.getOverlay() == null ? new JsonPrimitive(true) : null);
            case "no_world":
                return waiter.until("the world to be left", timeout,
                        () -> client.world == null && client.currentScreen instanceof TitleScreen
                                ? new JsonPrimitive(true) : null);
            case "chat":
                long since = Args.number(args, "since", chat.sequence());
                return waiter.until("a chat line containing \"" + value + "\"", timeout,
                        () -> chat.firstContaining(since, value));
            case "ticks":
                int ticks = Math.max(1, value.isEmpty() ? 1 : Args.integer(args, "value"));
                long[] left = {ticks};
                return waiter.until(ticks + " tick(s)", Math.max(timeout, ticks * 100L),
                        () -> --left[0] < 0 ? new JsonPrimitive(ticks) : null);
            default:
                throw new Ops.Refused("\"for\" is one of screen, no_screen, world, no_world, loaded, chat, ticks");
        }
    }

    // ---- helpers ---------------------------------------------------------------------

    /**
     * A container screen's registered type, which is the same in a
     * development run and a shipped jar. A class name is not: MerchantScreen
     * is class_492 to a player, and a scenario that waited for it by name
     * worked for its author and nobody else.
     */
    static String handlerTypeOf(Screen screen) {
        if (!(screen instanceof HandledScreen<?> handled)) {
            return null;
        }
        try {
            var id = Registries.SCREEN_HANDLER.getId(handled.getScreenHandler().getType());
            return id == null ? null : id.toString();
        } catch (UnsupportedOperationException untyped) {
            // The player's own inventory has no type; it is never opened by a server.
            return "minecraft:player_inventory";
        }
    }

    static String titleKeyOf(Screen screen) {
        return screen.getTitle().getContent() instanceof net.minecraft.text.TranslatableTextContent translated
                ? translated.getKey() : null;
    }

    /** Whether a screen is the one meant: by handler type or title key first, then title, then class. */
    static boolean screenIs(Screen screen, String wantedLowerCase) {
        String handlerType = handlerTypeOf(screen);
        String titleKey = titleKeyOf(screen);
        return (handlerType != null && handlerType.contains(wantedLowerCase))
                || (titleKey != null && titleKey.toLowerCase(Locale.ROOT).contains(wantedLowerCase))
                || screen.getTitle().getString().toLowerCase(Locale.ROOT).contains(wantedLowerCase)
                || screen.getClass().getName().toLowerCase(Locale.ROOT).contains(wantedLowerCase);
    }

    /** A point on screen from {x, y}, {widget: index|text} or {slot: n}, in scaled GUI pixels. */
    static double[] pointOf(MinecraftClient client, JsonObject args) throws Ops.Refused {
        if (args.has("x") && args.has("y")) {
            return new double[] {Args.decimal(args, "x"), Args.decimal(args, "y")};
        }
        Screen screen = requireScreen(client);
        if (args.has("slot")) {
            if (!(screen instanceof HandledScreen<?> handled)) {
                throw new Ops.Refused("no container screen is open");
            }
            int slot = Args.integer(args, "slot");
            var slots = handled.getScreenHandler().slots;
            if (slot < 0 || slot >= slots.size()) {
                throw new Ops.Refused("the container has " + slots.size() + " slots; there is no slot " + slot);
            }
            HandledScreenAccessor bounds = (HandledScreenAccessor) handled;
            return new double[] {bounds.mc_puppet$x() + slots.get(slot).x + 8,
                    bounds.mc_puppet$y() + slots.get(slot).y + 8};
        }
        if (args.has("widget")) {
            ClickableWidget widget = widgetBy(screen, args.get("widget").getAsString());
            return new double[] {widget.getX() + widget.getWidth() / 2.0, widget.getY() + widget.getHeight() / 2.0};
        }
        throw new Ops.Refused("a place is {x, y}, {widget: index or text} or {slot: n}");
    }

    static ClickableWidget widgetBy(Screen screen, String indexOrText) throws Ops.Refused {
        List<ClickableWidget> widgets = widgetsOf(screen);
        if (indexOrText.matches("\\d+")) {
            int index = Integer.parseInt(indexOrText);
            if (index >= widgets.size()) {
                throw new Ops.Refused("the screen has " + widgets.size() + " widgets; there is no index " + index);
            }
            return widgets.get(index);
        }
        String wanted = indexOrText.toLowerCase(Locale.ROOT);
        for (ClickableWidget widget : widgets) {
            if (widget.visible && widget.getMessage().getString().toLowerCase(Locale.ROOT).contains(wanted)) {
                return widget;
            }
        }
        throw new Ops.Refused("no visible widget says \"" + indexOrText + "\"");
    }

    private static CompletableFuture<JsonElement> drag(MinecraftClient client, Waiter waiter, JsonObject args)
            throws Ops.Refused {
        if (!args.has("from") || !args.get("from").isJsonObject() || !args.has("to")) {
            throw new Ops.Refused("drag takes \"from\" and \"to\"");
        }
        double[] from = pointOf(client, args.getAsJsonObject("from"));
        List<double[]> path = new ArrayList<>();
        if (args.get("to").isJsonArray()) {
            for (JsonElement each : args.getAsJsonArray("to")) {
                path.add(pointOf(client, each.getAsJsonObject()));
            }
        } else {
            path.add(pointOf(client, args.getAsJsonObject("to")));
        }
        if (path.isEmpty()) {
            throw new Ops.Refused("\"to\" names no place");
        }
        int button = Input.buttonOf(args);
        List<Integer> modifiers = new ArrayList<>();
        if (args.has("modifiers") && args.get("modifiers").isJsonArray()) {
            for (JsonElement each : args.getAsJsonArray("modifiers")) {
                modifiers.add(VirtualKeys.code(each.getAsString()));
            }
        }
        List<Runnable> steps = new ArrayList<>();
        steps.add(() -> {
            modifiers.forEach(VirtualKeys::hold);
            Input.moveTo(client, from[0], from[1]);
            Input.button(client, button, true);
        });
        for (double[] point : path) {
            steps.add(() -> Input.moveTo(client, point[0], point[1]));
        }
        double[] last = path.get(path.size() - 1);
        steps.add(() -> {
            Input.moveTo(client, last[0], last[1]);
            Input.button(client, button, false);
            modifiers.forEach(VirtualKeys::release);
        });
        return Input.overTicks(waiter, "the drag to finish", steps, () -> new JsonPrimitive(path.size()));
    }

    private static Screen requireScreen(MinecraftClient client) throws Ops.Refused {
        if (client.currentScreen == null) {
            throw new Ops.Refused("no screen is open");
        }
        return client.currentScreen;
    }

    private static ClientPlayerEntity requirePlayer(MinecraftClient client) throws Ops.Refused {
        if (client.player == null || client.world == null) {
            throw new Ops.Refused("no world is loaded");
        }
        return client.player;
    }

    private static String stripSlash(String command) {
        return command.startsWith("/") ? command.substring(1) : command;
    }
}
