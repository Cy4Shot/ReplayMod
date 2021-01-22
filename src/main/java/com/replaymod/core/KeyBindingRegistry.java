package com.replaymod.core;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.replaymod.core.events.PreRenderCallback;
import com.replaymod.mixin.KeyBindingAccessor;
import com.replaymod.gui.utils.EventRegistrations;
import com.replaymod.core.events.KeyBindingEventCallback;
import com.replaymod.core.events.KeyEventCallback;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.crash.CrashReport;
import net.minecraft.crash.CrashReportCategory;
import net.minecraft.crash.ReportedException;

//#if FABRIC>=1
//$$ import com.replaymod.core.versions.LangResourcePack;
//$$ import net.fabricmc.fabric.api.client.keybinding.FabricKeyBinding;
//$$ import net.minecraft.client.util.InputUtil;
//$$ import net.minecraft.util.Identifier;
//$$ import static com.replaymod.core.ReplayMod.MOD_ID;
//#else
import net.minecraftforge.fml.client.registry.ClientRegistry;
//#endif

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class KeyBindingRegistry extends EventRegistrations {
    private static final String CATEGORY = "replaymod.title";
    //#if FABRIC>=1
    //$$ static { net.fabricmc.fabric.api.client.keybinding.KeyBindingRegistry.INSTANCE.addCategory(CATEGORY); }
    //#endif

    private final Map<String, Binding> bindings = new HashMap<>();
    private Set<KeyBinding> onlyInReplay = new HashSet<>();
    private Multimap<Integer, Supplier<Boolean>> rawHandlers = ArrayListMultimap.create();

    public Binding registerKeyBinding(String name, int keyCode, Runnable whenPressed, boolean onlyInRepay) {
        Binding binding = registerKeyBinding(name, keyCode, onlyInRepay);
        binding.handlers.add(whenPressed);
        return binding;
    }

    public Binding registerRepeatedKeyBinding(String name, int keyCode, Runnable whenPressed, boolean onlyInRepay) {
        Binding binding = registerKeyBinding(name, keyCode, onlyInRepay);
        binding.repeatedHandlers.add(whenPressed);
        return binding;
    }

    private Binding registerKeyBinding(String name, int keyCode, boolean onlyInRepay) {
        Binding binding = bindings.get(name);
        if (binding == null) {
            //#if FABRIC>=1
            //$$ if (keyCode == 0) {
            //$$     keyCode = -1;
            //$$ }
            //$$ Identifier id = new Identifier(MOD_ID, name.substring(LangResourcePack.LEGACY_KEY_PREFIX.length()));
            //$$ FabricKeyBinding fabricKeyBinding = FabricKeyBinding.Builder.create(id, InputUtil.Type.KEYSYM, keyCode, CATEGORY).build();
            //$$ net.fabricmc.fabric.api.client.keybinding.KeyBindingRegistry.INSTANCE.register(fabricKeyBinding);
            //$$ KeyBinding keyBinding = fabricKeyBinding;
            //#else
            KeyBinding keyBinding = new KeyBinding(name, keyCode, CATEGORY);
            ClientRegistry.registerKeyBinding(keyBinding);
            //#endif
            binding = new Binding(name, keyBinding);
            bindings.put(name, binding);
            if (onlyInRepay) {
                this.onlyInReplay.add(keyBinding);
            }
        } else if (!onlyInRepay) {
            this.onlyInReplay.remove(binding.keyBinding);
        }
        return binding;
    }

    public void registerRaw(int keyCode, Supplier<Boolean> whenPressed) {
        rawHandlers.put(keyCode, whenPressed);
    }

    public Map<String, Binding> getBindings() {
        return Collections.unmodifiableMap(bindings);
    }

    public Set<KeyBinding> getOnlyInReplay() {
        return Collections.unmodifiableSet(onlyInReplay);
    }

    { on(PreRenderCallback.EVENT, this::handleRepeatedKeyBindings); }

    public void handleRepeatedKeyBindings() {
        for (Binding binding : bindings.values()) {
            if (binding.keyBinding.isKeyDown()) {
                invokeKeyBindingHandlers(binding, binding.repeatedHandlers);
            }
        }
    }

    { on(KeyBindingEventCallback.EVENT, this::handleKeyBindings); }
    private void handleKeyBindings() {
        for (Binding binding : bindings.values()) {
            while (binding.keyBinding.isPressed()) {
                invokeKeyBindingHandlers(binding, binding.handlers);
                invokeKeyBindingHandlers(binding, binding.repeatedHandlers);
            }
        }
    }

    private void invokeKeyBindingHandlers(Binding binding, Collection<Runnable> handlers) {
        for (final Runnable runnable : handlers) {
            try {
                runnable.run();
            } catch (Throwable cause) {
                CrashReport crashReport = CrashReport.makeCrashReport(cause, "Handling Key Binding");
                CrashReportCategory category = crashReport.makeCategory("Key Binding");
                category.addDetail("Key Binding", () -> binding.name);
                category.addDetail("Handler", runnable::toString);
                throw new ReportedException(crashReport);
            }
        }
    }

    { on(KeyEventCallback.EVENT, (keyCode, scanCode, action, modifiers) -> handleRaw(keyCode, action)); }
    private boolean handleRaw(int keyCode, int action) {
        if (action != KeyEventCallback.ACTION_PRESS) return false;
        for (final Supplier<Boolean> handler : rawHandlers.get(keyCode)) {
            try {
                if (handler.get()) {
                    return true;
                }
            } catch (Throwable cause) {
                CrashReport crashReport = CrashReport.makeCrashReport(cause, "Handling Raw Key Binding");
                CrashReportCategory category = crashReport.makeCategory("Key Binding");
                category.addDetail("Key Code", () -> "" + keyCode);
                category.addDetail("Handler", handler::toString);
                throw new ReportedException(crashReport);
            }
        }
        return false;
    }

    public class Binding {
        public final String name;
        public final KeyBinding keyBinding;
        private final List<Runnable> handlers = new ArrayList<>();
        private final List<Runnable> repeatedHandlers = new ArrayList<>();
        private boolean autoActivation;
        private Consumer<Boolean> autoActivationUpdate;

        public Binding(String name, KeyBinding keyBinding) {
            this.name = name;
            this.keyBinding = keyBinding;
        }

        public String getBoundKey() {
            try {
                return keyBinding.func_238171_j_().getString();
            } catch (ArrayIndexOutOfBoundsException e) {
                // Apparently windows likes to press strange keys, see https://www.replaymod.com/forum/thread/55
                return "Unknown";
            }
        }

        public boolean isBound() {
            //#if MC>=11400
            return !keyBinding.isInvalid();
            //#else
            //$$ return keyBinding.getKeyCode() != 0;
            //#endif
        }

        public void trigger() {
            KeyBindingAccessor acc = (KeyBindingAccessor) keyBinding;
            acc.setPressTime(acc.getPressTime() + 1);
            handleKeyBindings();
        }

        public void registerAutoActivationSupport(boolean active, Consumer<Boolean> update) {
            this.autoActivation = active;
            this.autoActivationUpdate = update;
        }

        public boolean supportsAutoActivation() {
            return autoActivationUpdate != null;
        }

        public boolean isAutoActivating() {
            return supportsAutoActivation() && autoActivation;
        }

        public void setAutoActivating(boolean active) {
            if (this.autoActivation == active) {
                return;
            }
            this.autoActivation = active;
            this.autoActivationUpdate.accept(active);
        }
    }
}
