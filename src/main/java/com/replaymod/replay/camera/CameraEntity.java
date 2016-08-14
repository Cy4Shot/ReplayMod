package com.replaymod.replay.camera;

import com.replaymod.core.events.SettingsChangedEvent;
import com.replaymod.replay.ReplayModReplay;
import com.replaymod.replay.Setting;
import eu.crushedpixel.replaymod.holders.AdvancedPosition;
import eu.crushedpixel.replaymod.utils.SkinProvider;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.item.EntityItemFrame;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.stats.StatFileWriter;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.UUID;
import java.util.function.Function;

/**
 * The camera entity used as the main player entity during replay viewing.
 * During a replay {@link Minecraft#thePlayer} should be an instance of this class.
 * Camera movement is controlled by a separate {@link CameraController}.
 */
public class CameraEntity extends EntityPlayerSP {
    /**
     * Roll of this camera in degrees.
     */
    public float roll;

    @Getter
    @Setter
    private CameraController cameraController;

    private long lastControllerUpdate = System.currentTimeMillis();

    /**
     * The entity whose hand was the last one rendered.
     */
    private Entity lastHandRendered = null;

    /**
     * The hashCode and equals methods of Entity are not stable.
     * Therefore we cannot register any event handlers directly in the CameraEntity class and
     * instead have this inner class.
     */
    private final EventHandler eventHandler = new EventHandler();

    public CameraEntity(Minecraft mcIn, World worldIn, NetHandlerPlayClient netHandlerPlayClient, StatFileWriter statFileWriter) {
        super(mcIn, worldIn, netHandlerPlayClient, statFileWriter);
        FMLCommonHandler.instance().bus().register(eventHandler);
        MinecraftForge.EVENT_BUS.register(eventHandler);
        cameraController = ReplayModReplay.instance.createCameraController(this);
    }

    /**
     * Moves the camera by the specified delta.
     * @param x Delta in X direction
     * @param y Delta in Y direction
     * @param z Delta in Z direction
     */
    public void moveCamera(double x, double y, double z) {
        setCameraPosition(posX + x, posY + y, posZ + z);
    }

    /**
     * Set the camera position.
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     */
    public void setCameraPosition(double x, double y, double z) {
        this.lastTickPosX = this.prevPosX = this.posX = x;
        this.lastTickPosY = this.prevPosY = this.posY = y;
        this.lastTickPosZ = this.prevPosZ = this.posZ = z;
        updateBoundingBox();
    }

    /**
     * Sets the camera rotation.
     * @param yaw Yaw in degrees
     * @param pitch Pitch in degrees
     * @param roll Roll in degrees
     */
    public void setCameraRotation(float yaw, float pitch, float roll) {
        this.prevRotationYaw = this.rotationYaw = yaw;
        this.prevRotationPitch = this.rotationPitch = pitch;
        this.roll = roll;
    }

    /**
     * Sets the camera position and rotation to that of the specified AdvancedPosition
     * @param pos The position and rotation to set
     */
    public void setCameraPosRot(AdvancedPosition pos) {
        setCameraRotation((float) pos.getYaw(), (float) pos.getPitch(), (float) pos.getRoll());
        setCameraPosition(pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * Sets the camera position and rotation to that of the specified entity.
     * @param to The entity whose position to copy
     */
    public void setCameraPosRot(Entity to) {
        prevPosX = to.prevPosX;
        prevPosY = to.prevPosY;
        prevPosZ = to.prevPosZ;
        prevRotationYaw = to.prevRotationYaw;
        prevRotationPitch = to.prevRotationPitch;
        posX = to.posX;
        posY = to.posY;
        posZ = to.posZ;
        rotationYaw = to.rotationYaw;
        rotationPitch = to.rotationPitch;
        lastTickPosX = to.lastTickPosX;
        lastTickPosY = to.lastTickPosY;
        lastTickPosZ = to.lastTickPosZ;
        updateBoundingBox();
    }

    private void updateBoundingBox() {
        setEntityBoundingBox(new AxisAlignedBB(
                posX - width / 2, posY, posZ - width / 2,
                posX + width / 2, posY + height, posZ + width / 2));
    }

    @Override
    public void onUpdate() {
        Entity view = mc.getRenderViewEntity();
        if (view != null) {
            // Make sure we're always spectating the right entity
            // This is important if the spectated player respawns as their
            // entity is recreated and we have to spectate a new entity
            UUID spectating = ReplayModReplay.instance.getReplayHandler().getSpectatedUUID();
            if (spectating != null && (view.getUniqueID() != spectating || view.worldObj != worldObj)) {
                view = worldObj.getPlayerEntityByUUID(spectating);
                if (view != null) {
                    mc.setRenderViewEntity(view);
                } else {
                    mc.setRenderViewEntity(this);
                    return;
                }
            }
            // Move cmera to their position so when we exit the first person view
            // we don't jump back to where we entered it
            if (view != this) {
                setCameraPosRot(view);
            }
        }
    }

    @Override
    public void preparePlayerToSpawn() {
        // Make sure our world is up-to-date in case of world changes
        if (mc.theWorld != null) {
            worldObj = mc.theWorld;
        }
        super.preparePlayerToSpawn();
    }

    @Override
    public void setAngles(float yaw, float pitch) {
        if (mc.getRenderViewEntity() == this) {
            // Only update camera rotation when the camera is the view
            super.setAngles(yaw, pitch);
        }
    }

    @Override
    public boolean isEntityInsideOpaqueBlock() {
        return falseUnlessSpectating(Entity::isEntityInsideOpaqueBlock); // Make sure no suffocation overlay is rendered
    }

    @Override
    public boolean isInsideOfMaterial(Material materialIn) {
        return falseUnlessSpectating(e -> e.isInsideOfMaterial(materialIn)); // Make sure no overlays are rendered
    }

    @Override
    public boolean isInLava() {
        return falseUnlessSpectating(Entity::isInLava); // Make sure no lava overlay is rendered
    }

    @Override
    public boolean isInWater() {
        return falseUnlessSpectating(Entity::isInWater); // Make sure no water overlay is rendered
    }

    @Override
    public boolean isBurning() {
        return falseUnlessSpectating(Entity::isBurning); // Make sure no fire overlay is rendered
    }

    private boolean falseUnlessSpectating(Function<Entity, Boolean> property) {
        Entity view = mc.getRenderViewEntity();
        if (view != null && view != this) {
            return property.apply(view);
        }
        return false;
    }

    @Override
    public boolean canBePushed() {
        return false; // We are in full control of ourselves
    }

    @Override
    protected void createRunningParticles() {
        // We do not produce any particles, we are a camera
    }

    @Override
    public boolean canBeCollidedWith() {
        return false; // We are a camera, we cannot collide
    }

    @Override
    public boolean isSpectator() {
        return ReplayModReplay.instance.getReplayHandler().isCameraView(); // Make sure we're treated as spectator
    }

    @Override
    public ResourceLocation getLocationSkin() {
        Entity view = mc.getRenderViewEntity();
        if (view != this && view instanceof EntityPlayer) {
            return SkinProvider.getResourceLocationForPlayerUUID(view.getUniqueID());
        }
        return super.getLocationSkin();
    }

    @Override
    public float getSwingProgress(float renderPartialTicks) {
        Entity view = mc.getRenderViewEntity();
        if (view != this && view instanceof EntityPlayer) {
            return ((EntityPlayer) view).getSwingProgress(renderPartialTicks);
        }
        return 0;
    }

    @Override
    public MovingObjectPosition rayTrace(double p_174822_1_, float p_174822_3_) {
        MovingObjectPosition pos = super.rayTrace(p_174822_1_, 1f);

        // Make sure we can never look at blocks (-> no outline)
        if(pos != null && pos.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            pos.typeOfHit = MovingObjectPosition.MovingObjectType.MISS;
        }

        return pos;
    }

    @Override
    public void setDead() {
        super.setDead();
        FMLCommonHandler.instance().bus().unregister(eventHandler);
        MinecraftForge.EVENT_BUS.unregister(eventHandler);
    }

    private void update() {
        long now = System.currentTimeMillis();
        long timePassed = now - lastControllerUpdate;
        cameraController.update(timePassed / 50f);
        lastControllerUpdate = now;

        if (mc.gameSettings.keyBindAttack.isPressed() || mc.gameSettings.keyBindUseItem.isPressed()) {
            if (canSpectate(mc.pointedEntity)) {
                ReplayModReplay.instance.getReplayHandler().spectateEntity(mc.pointedEntity);
                // Make sure we don't exit right away
                mc.gameSettings.keyBindSneak.pressTime = 0;
            }
        }
    }

    private void updateArmYawAndPitch() {
        prevRenderArmYaw = renderArmYaw;
        prevRenderArmPitch = renderArmPitch;
        renderArmPitch = renderArmPitch +  (rotationPitch - renderArmPitch) * 0.5f;
        renderArmYaw = renderArmYaw +  (rotationYaw - renderArmYaw) * 0.5f;
    }

    public boolean canSpectate(Entity e) {
        return e != null && !e.isInvisible()
                && (e instanceof EntityPlayer || e instanceof EntityLiving || e instanceof EntityItemFrame);
    }

    private class EventHandler {
        @SubscribeEvent
        public void onPreClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.START) {
                update();
                updateArmYawAndPitch();
            }
        }

        @SubscribeEvent
        public void onRenderUpdate(TickEvent.RenderTickEvent event) {
            if (event.phase == TickEvent.Phase.START) {
                update();
            }
        }

        @SubscribeEvent
        public void preCrosshairRender(RenderGameOverlayEvent.Pre event) {
            // The crosshair should only render if targeted entity can actually be spectated
            if (event.type == RenderGameOverlayEvent.ElementType.CROSSHAIRS) {
                event.setCanceled(!canSpectate(mc.pointedEntity));
            }
        }

        @SubscribeEvent
        public void onSettingsChanged(SettingsChangedEvent event) {
            if (event.getKey() == Setting.CAMERA) {
                cameraController = ReplayModReplay.instance.createCameraController(CameraEntity.this);
            }
        }

        @SubscribeEvent
        public void onRenderHand(RenderHandEvent event) {
            // Unless we are spectating another player, don't render our hand
            if (mc.getRenderViewEntity() == CameraEntity.this || !(mc.getRenderViewEntity() instanceof EntityPlayer)) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void onRenderHandMonitor(RenderHandEvent event) {
            Entity view = mc.getRenderViewEntity();
            if (view instanceof EntityPlayer) {
                EntityPlayer player = (EntityPlayer) view;
                // When the spectated player has changed, force equip their items to prevent the equip animation
                if (lastHandRendered != player) {
                    lastHandRendered = player;

                    mc.entityRenderer.itemRenderer.prevEquippedProgress = 1;
                    mc.entityRenderer.itemRenderer.equippedProgress = 1;
                    mc.entityRenderer.itemRenderer.itemToRender = player.inventory.getCurrentItem();
                    mc.entityRenderer.itemRenderer.equippedItemSlot = player.inventory.currentItem;

                    mc.thePlayer.renderArmYaw = mc.thePlayer.prevRenderArmYaw = player.rotationYaw;
                    mc.thePlayer.renderArmPitch = mc.thePlayer.prevRenderArmPitch = player.rotationPitch;
                }
            }
        }

        @SubscribeEvent
        public void onEntityViewRenderEvent(EntityViewRenderEvent.CameraSetup event) {
            if (mc.getRenderViewEntity() == CameraEntity.this) {
                event.roll = roll;
            }
        }
    }
}
