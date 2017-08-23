package com.replaymod.replay;

import com.google.common.base.Preconditions;
import com.google.common.io.Files;
import com.replaymod.core.ReplayMod;
import com.replaymod.core.utils.Restrictions;
import com.replaymod.replay.camera.CameraEntity;
import com.replaymod.replaystudio.replay.ReplayFile;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiDownloadTerrain;
import net.minecraft.client.gui.GuiErrorScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.Entity;
import net.minecraft.network.*;
import net.minecraft.network.play.server.*;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.World;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraft.world.WorldType;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import java.io.*;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Sends replay packets to netty channels.
 * Even though {@link Sharable}, this should never be added to multiple pipes at once, it may however be re-added when
 * the replay restart from the beginning.
 */
@Sharable
public class ReplaySender extends ChannelInboundHandlerAdapter {

    /**
     * Previously packets for the client player were inserted using one fixed entity id (this one).
     * This is no longer the case however to provide backwards compatibility, we have to convert
     * these old packets to use the normal entity id.
     * Need to punch someone? -> CrushedPixel
     */
    public static final int LEGACY_ENTITY_ID = Integer.MIN_VALUE + 9001;

    /**
     * These packets are ignored completely during replay.
     */
    private static final List<Class> BAD_PACKETS = Arrays.<Class>asList(
            S06PacketUpdateHealth.class,
            S2DPacketOpenWindow.class,
            S2EPacketCloseWindow.class,
            S2FPacketSetSlot.class,
            S30PacketWindowItems.class,
            S36PacketSignEditorOpen.class,
            S37PacketStatistics.class,
            S1FPacketSetExperience.class,
            S43PacketCamera.class,
            S39PacketPlayerAbilities.class,
            S45PacketTitle.class);

    private static int TP_DISTANCE_LIMIT = 128;

    /**
     * The replay handler responsible for the current replay.
     */
    private final ReplayHandler replayHandler;

    /**
     * Whether to work in async mode.
     *
     * When in async mode, a separate thread send packets and waits according to their delays.
     * This is default in normal playback mode.
     *
     * When in sync mode, no packets will be sent until {@link #sendPacketsTill(int)} is called.
     * This is used during path playback and video rendering.
     */
    protected boolean asyncMode;

    /**
     * Timestamp of the last packet sent in milliseconds since the start.
     */
    protected int lastTimeStamp;

    /**
     * @see #currentTimeStamp()
     */
    protected int currentTimeStamp;

    /**
     * The replay file.
     */
    protected ReplayFile replayFile;

    /**
     * The channel handler context used to send packets to minecraft.
     */
    protected ChannelHandlerContext ctx;

    /**
     * The data input stream from which new packets are read.
     * When accessing this stream make sure to synchronize on {@code this} as it's used from multiple threads.
     */
    protected DataInputStream dis;

    /**
     * The next packet that should be sent.
     * This is required as some actions such as jumping to a specified timestamp have to peek at the next packet.
     */
    protected PacketData nextPacket;

    /**
     * Whether we need to restart the current replay. E.g. when jumping backwards in time
     */
    protected boolean startFromBeginning = true;

    /**
     * Whether to terminate the replay. This only has an effect on the async mode and is {@code true} during sync mode.
     */
    protected boolean terminate;

    /**
     * The speed of the replay. 1 is normal, 2 is twice as fast, 0.5 is half speed and 0 is frozen
     */
    protected double replaySpeed = 1f;

    /**
     * Whether the world has been loaded and the dirt-screen should go away.
     */
    protected boolean hasWorldLoaded;

    /**
     * The minecraft instance.
     */
    protected Minecraft mc = Minecraft.getMinecraft();

    /**
     * The total length of this replay in milliseconds.
     */
    protected final int replayLength;

    /**
     * Our actual entity id that the server gave to us.
     */
    protected int actualID = -1;

    /**
     * Whether to allow (process) the next player movement packet.
     */
    protected boolean allowMovement;

    /**
     * Directory to which resource packs are extracted.
     */
    private final File tempResourcePackFolder = Files.createTempDir();

    /**
     * Create a new replay sender.
     * @param file The replay file
     * @param asyncMode {@code true} for async mode, {@code false} otherwise
     * @see #asyncMode
     */
    public ReplaySender(ReplayHandler replayHandler, ReplayFile file, boolean asyncMode) throws IOException {
        this.replayHandler = replayHandler;
        this.replayFile = file;
        this.asyncMode = asyncMode;
        this.replayLength = file.getMetaData().getDuration();

        if (asyncMode) {
            new Thread(asyncSender, "replaymod-async-sender").start();
        }
    }

    /**
     * Set whether this replay sender operates in async mode.
     * When in async mode, it will send packets timed from a separate thread.
     * When not in async mode, it will send packets when {@link #sendPacketsTill(int)} is called.
     * @param asyncMode {@code true} to enable async mode
     */
    public void setAsyncMode(boolean asyncMode) {
        if (this.asyncMode == asyncMode) return;
        this.asyncMode = asyncMode;
        if (asyncMode) {
            this.terminate = false;
            new Thread(asyncSender, "replaymod-async-sender").start();
        } else {
            this.terminate = true;
        }
    }

    public boolean isAsyncMode() {
        return asyncMode;
    }

    /**
     * Set whether this replay sender  to operate in sync mode.
     * When in sync mode, it will send packets when {@link #sendPacketsTill(int)} is called.
     * This call will block until the async worker thread has stopped.
     */
    public void setSyncModeAndWait() {
        if (!this.asyncMode) return;
        this.asyncMode = false;
        this.terminate = true;
        synchronized (this) {
            // This will wait for the worker thread to leave the synchronized code part
        }
    }

    /**
     * Return a fake {@link Minecraft#getSystemTime()} value that respects slowdown/speedup/pause and works in both,
     * sync and async mode.
     * Note: For sync mode this returns the last value passed to {@link #sendPacketsTill(int)}.
     * @return The timestamp in milliseconds since the start of the replay
     */
    public int currentTimeStamp() {
        if (asyncMode) {
            int timePassed = (int) (System.currentTimeMillis() - lastPacketSent);
            return lastTimeStamp + (int) (timePassed * getReplaySpeed());
        } else {
            return lastTimeStamp;
        }
    }

    /**
     * Return the total length of the replay played.
     * @return Total length in milliseconds
     */
    public int replayLength() {
        return replayLength;
    }

    /**
     * Terminate this replay sender.
     */
    public void terminateReplay() {
        terminate = true;
        try {
            channelInactive(ctx);
            ctx.channel().pipeline().close();
            FileUtils.deleteDirectory(tempResourcePackFolder);
        } catch(Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg)
            throws Exception {
        // When in async mode and the replay sender shut down, then don't send packets
        if(terminate && asyncMode) {
            return;
        }

        // When a packet is sent directly, perform no filtering
        if(msg instanceof Packet) {
            super.channelRead(ctx, msg);
        }

        if (msg instanceof byte[]) {
            try {
                Packet p = deserializePacket((byte[]) msg);

                if (p != null) {
                    p = processPacket(p);
                    if (p != null) {
                        super.channelRead(ctx, p);
                    }

                    // If we do not give minecraft time to tick, there will be dead entity artifacts left in the world
                    // Therefore we have to remove all loaded, dead entities manually if we are in sync mode.
                    // We do this after every SpawnX packet and after the destroy entities packet.
                    if (!asyncMode && mc.theWorld != null) {
                        if (p instanceof S0CPacketSpawnPlayer
                                || p instanceof S0EPacketSpawnObject
                                || p instanceof S0FPacketSpawnMob
                                || p instanceof S2CPacketSpawnGlobalEntity
                                || p instanceof S10PacketSpawnPainting
                                || p instanceof S11PacketSpawnExperienceOrb
                                || p instanceof S13PacketDestroyEntities) {
                            World world = mc.theWorld;
                            for (int i = 0; i < world.loadedEntityList.size(); ++i) {
                                Entity entity = (Entity) world.loadedEntityList.get(i);
                                if (entity.isDead) {
                                    int chunkX = entity.chunkCoordX;
                                    int chunkY = entity.chunkCoordZ;

                                    if (entity.addedToChunk && world.getChunkProvider().chunkExists(chunkX, chunkY)) {
                                        world.getChunkFromChunkCoords(chunkX, chunkY).removeEntity(entity);
                                    }

                                    world.loadedEntityList.remove(i--);
                                    world.onEntityRemoved(entity);
                                }

                            }
                        }
                    }
                }
            } catch (Exception e) {
                // We'd rather not have a failure parsing one packet screw up the whole replay process
                e.printStackTrace();
            }
        }

    }

    private Packet deserializePacket(byte[] bytes) throws IOException, IllegalAccessException, InstantiationException {
        ByteBuf bb = Unpooled.wrappedBuffer(bytes);
        PacketBuffer pb = new PacketBuffer(bb);

        int i = pb.readVarIntFromBuffer();

        Packet p = EnumConnectionState.PLAY.getPacket(EnumPacketDirection.CLIENTBOUND, i);
        p.readPacketData(pb);

        return p;
    }

    /**
     * Process a packet and return the result.
     * @param p The packet to process
     * @return The processed packet or {@code null} if no packet shall be sent
     */
    protected Packet processPacket(Packet p) throws Exception {
        if (p instanceof S3FPacketCustomPayload) {
            S3FPacketCustomPayload packet = (S3FPacketCustomPayload) p;
            if (Restrictions.PLUGIN_CHANNEL.equals(packet.getChannelName())) {
                final String unknown = replayHandler.getRestrictions().handle(packet);
                if (unknown == null) {
                    return null;
                } else {
                    // Failed to parse options, make sure that under no circumstances further packets are parsed
                    terminateReplay();
                    // Then end replay and show error GUI
                    mc.addScheduledTask(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                replayHandler.endReplay();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                            mc.displayGuiScreen(new GuiErrorScreen(
                                    I18n.format("replaymod.error.unknownrestriction1"),
                                    I18n.format("replaymod.error.unknownrestriction2", unknown)
                            ));
                        }
                    });
                }
            }
        }
        if (p instanceof S40PacketDisconnect) {
            IChatComponent reason = ((S40PacketDisconnect) p).func_149165_c();
            if ("Please update to view this replay.".equals(reason.getUnformattedText())) {
                // This version of the mod supports replay restrictions so we are allowed
                // to remove this packet.
                return null;
            }
        }

        if(BAD_PACKETS.contains(p.getClass())) return null;

        convertLegacyEntityIds(p);

        if(p instanceof S48PacketResourcePackSend) {
            S48PacketResourcePackSend packet = (S48PacketResourcePackSend) p;
            String url = packet.func_179783_a();
            if (url.startsWith("replay://")) {
                int id = Integer.parseInt(url.substring("replay://".length()));
                Map<Integer, String> index = replayFile.getResourcePackIndex();
                if (index != null) {
                    String hash = index.get(id);
                    if (hash != null) {
                        File file = new File(tempResourcePackFolder, hash + ".zip");
                        if (!file.exists()) {
                            IOUtils.copy(replayFile.getResourcePack(hash).get(), new FileOutputStream(file));
                        }
                        mc.getResourcePackRepository().func_177319_a(file);
                    }
                }
                return null;
            }
        }

        if(p instanceof S01PacketJoinGame) {
            S01PacketJoinGame packet = (S01PacketJoinGame) p;
            allowMovement = true;
            int entId = packet.getEntityId();
            actualID = entId;
            entId = -1789435; // Camera entity id should be negative which is an invalid id and can't be used by servers
            int dimension = packet.getDimension();
            EnumDifficulty difficulty = packet.getDifficulty();
            int maxPlayers = packet.getMaxPlayers();
            WorldType worldType = packet.getWorldType();

            p = new S01PacketJoinGame(entId, GameType.SPECTATOR, false, dimension,
                    difficulty, maxPlayers, worldType, false);
        }

        if(p instanceof S07PacketRespawn) {
            S07PacketRespawn respawn = (S07PacketRespawn) p;
            p = new S07PacketRespawn(respawn.func_149082_c(),
                    respawn.func_149081_d(), respawn.func_149080_f(), GameType.SPECTATOR);

            allowMovement = true;
        }

        if(p instanceof S08PacketPlayerPosLook) {
            if(!hasWorldLoaded) hasWorldLoaded = true;
            final S08PacketPlayerPosLook ppl = (S08PacketPlayerPosLook) p;

            if (mc.currentScreen instanceof GuiDownloadTerrain) {
                // Close the world loading screen manually in case we swallow the packet
                mc.displayGuiScreen(null);
            }

            if(replayHandler.shouldSuppressCameraMovements()) return null;

            CameraEntity cent = replayHandler.getCameraEntity();

            for (Object relative : ppl.func_179834_f()) {
                if (relative == S08PacketPlayerPosLook.EnumFlags.X
                        || relative == S08PacketPlayerPosLook.EnumFlags.Y
                        || relative == S08PacketPlayerPosLook.EnumFlags.Z) {
                    return null; // At least one of the coordinates is relative, so we don't care
                }
            }

            if(cent != null) {
                if(!allowMovement && !((Math.abs(cent.posX - ppl.func_148932_c()) > TP_DISTANCE_LIMIT) ||
                        (Math.abs(cent.posZ - ppl.func_148933_e()) > TP_DISTANCE_LIMIT))) {
                    return null;
                } else {
                    allowMovement = false;
                }
            }

            new Runnable() {
                @Override
                @SuppressWarnings("unchecked")
                public void run() {
                    if (mc.theWorld == null || !mc.isCallingFromMinecraftThread()) {
                        ReplayMod.instance.runLater(this);
                        return;
                    }

                    CameraEntity cent = replayHandler.getCameraEntity();
                    cent.setCameraPosition(ppl.func_148932_c(), ppl.func_148928_d(), ppl.func_148933_e());
                }
            }.run();
        }

        if(p instanceof S2BPacketChangeGameState) {
            S2BPacketChangeGameState pg = (S2BPacketChangeGameState)p;
            int reason = pg.func_149138_c();

            // only allow the following packets:
            // 1 - End raining
            // 2 - Begin raining
            //
            // The following values are to control sky color (e.g. if thunderstorm)
            // 7 - Fade value
            // 8 - Fade time
            if(!(reason == 1 || reason == 2 || reason == 7 || reason == 8)) {
                return null;
            }
        }

        if (p instanceof S02PacketChat) {
            if (!ReplayModReplay.instance.getCore().getSettingsRegistry().get(Setting.SHOW_CHAT)) {
                return null;
            }
        }

        return asyncMode ? processPacketAsync(p) : processPacketSync(p);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        this.ctx = ctx;
        ctx.attr(NetworkManager.attrKeyConnectionState).set(EnumConnectionState.PLAY);
        super.channelActive(ctx);
    }

    /**
     * Whether the replay is currently paused.
     * @return {@code true} if it is paused, {@code false} otherwise
     */
    public boolean paused() {
        return mc.timer.timerSpeed == 0;
    }

    /**
     * Returns the speed of the replay. 1 being normal speed, 0.5 half and 2 twice as fast.
     * If 0 is returned, the replay is paused.
     * @return speed multiplier
     */
    public double getReplaySpeed() {
        if(!paused()) return replaySpeed;
        else return 0;
    }

    /**
     * Set the speed of the replay. 1 being normal speed, 0.5 half and 2 twice as fast.
     * The speed may not be set to 0 nor to negative values.
     * @param d Speed multiplier
     */
    public void setReplaySpeed(final double d) {
        if(d != 0) this.replaySpeed = d;
        mc.timer.timerSpeed = (float) d;
    }

    /////////////////////////////////////////////////////////
    //       Asynchronous packet processing                //
    /////////////////////////////////////////////////////////

    /**
     * The real time at which the last packet was sent in milliseconds.
     */
    private long lastPacketSent;

    /**
     * There is no waiting performed until a packet with at least this timestamp is reached (but not yet sent).
     * If this is -1, then timing is normal.
     */
    private long desiredTimeStamp = -1;

    /**
     * Runnable which performs timed dispatching of packets from the input stream.
     */
    private Runnable asyncSender = new Runnable() {
        public void run() {
            try {
                while (ctx == null && !terminate) {
                    Thread.sleep(10);
                }
                REPLAY_LOOP:
                while (!terminate) {
                    synchronized (ReplaySender.this) {
                        if (dis == null) {
                            dis = new DataInputStream(replayFile.getPacketData());
                        }
                        // Packet loop
                        while (true) {
                            try {
                                // When playback is paused and the world has loaded (we don't want any dirt-screens) we sleep
                                while (paused() && hasWorldLoaded) {
                                    // Unless we are going to terminate, restart or jump
                                    if (terminate || startFromBeginning || desiredTimeStamp != -1) {
                                        break;
                                    }
                                    Thread.sleep(10);
                                }

                                if (terminate) {
                                    break REPLAY_LOOP;
                                }

                                if (startFromBeginning) {
                                    // In case we need to restart from the beginning
                                    // break out of the loop sending all packets which will
                                    // cause the replay to be restarted by the outer loop
                                    break;
                                }

                                // Read the next packet if we don't already have one
                                if (nextPacket == null) {
                                    nextPacket = new PacketData(dis);
                                }

                                int nextTimeStamp = nextPacket.timestamp;

                                // If we aren't jumping and the world has already been loaded (no dirt-screens) then wait
                                // the required amount to get proper packet timing
                                if (!isHurrying() && hasWorldLoaded) {
                                    // How much time should have passed
                                    int timeWait = (int) Math.round((nextTimeStamp - lastTimeStamp) / replaySpeed);
                                    // How much time did pass
                                    long timeDiff = System.currentTimeMillis() - lastPacketSent;
                                    // How much time we need to wait to make up for the difference
                                    long timeToSleep = Math.max(0, timeWait - timeDiff);

                                    Thread.sleep(timeToSleep);
                                    lastPacketSent = System.currentTimeMillis();
                                }

                                // Process packet
                                channelRead(ctx, nextPacket.bytes);
                                nextPacket = null;

                                lastTimeStamp = nextTimeStamp;

                                // In case we finished jumping
                                // We need to check that we aren't planing to restart so we don't accidentally run this
                                // code before we actually restarted
                                if (isHurrying() && lastTimeStamp > desiredTimeStamp && !startFromBeginning) {
                                    desiredTimeStamp = -1;

                                    replayHandler.moveCameraToTargetPosition();

                                    // Pause after jumping
                                    setReplaySpeed(0);
                                }
                            } catch (EOFException eof) {
                                // Reached end of file
                                // Pause the replay which will cause it to freeze before getting restarted
                                setReplaySpeed(0);
                                // Then wait until the user tells us to continue
                                while (paused() && hasWorldLoaded && desiredTimeStamp == -1 && !terminate) {
                                    Thread.sleep(10);
                                }
                                break;
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }

                        // Restart the replay.
                        hasWorldLoaded = false;
                        lastTimeStamp = 0;
                        startFromBeginning = false;
                        nextPacket = null;
                        lastPacketSent = System.currentTimeMillis();
                        replayHandler.restartedReplay();
                        if (dis != null) {
                            dis.close();
                            dis = null;
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    };

    /**
     * Return whether this replay sender is currently rushing. When rushing, all packets are sent without waiting until
     * a specified timestamp is passed.
     * @return {@code true} if currently rushing, {@code false} otherwise
     */
    public boolean isHurrying() {
        return desiredTimeStamp != -1;
    }

    /**
     * Cancels the hurrying.
     */
    public void stopHurrying() {
        desiredTimeStamp = -1;
    }

    /**
     * Return the timestamp to which this replay sender is currently rushing. All packets with an lower or equal
     * timestamp will be sent out without any sleeping.
     * @return The timestamp in milliseconds since the start of the replay
     */
    public long getDesiredTimestamp() {
        return desiredTimeStamp;
    }

    /**
     * Jumps to the specified timestamp when in async mode by rushing all packets until one with a timestamp greater
     * than the specified timestamp is found.
     * If the timestamp has already passed, this causes the replay to restart and then rush all packets.
     * @param millis Timestamp in milliseconds since the start of the replay
     */
    public void jumpToTime(int millis) {
        Preconditions.checkState(asyncMode, "Can only jump in async mode. Use sendPacketsTill(int) instead.");
        if(millis < lastTimeStamp && !isHurrying()) {
            startFromBeginning = true;
        }

        desiredTimeStamp = millis;
    }

    protected Packet processPacketAsync(Packet p) {
        //If hurrying, ignore some packets, except for short durations
        if(desiredTimeStamp - lastTimeStamp > 1000) {
            if(p instanceof S2APacketParticles) return null;

            if(p instanceof S0EPacketSpawnObject) {
                S0EPacketSpawnObject pso = (S0EPacketSpawnObject)p;
                int type = pso.func_148993_l();
                if(type == 76) { // Firework rocket
                    return null;
                }
            }
        }
        return p;
    }

    /////////////////////////////////////////////////////////
    //        Synchronous packet processing                //
    /////////////////////////////////////////////////////////

    /**
     * Sends all packets until the specified timestamp is reached (inclusive).
     * If the timestamp is smaller than the last packet sent, the replay is restarted from the beginning.
     * @param timestamp The timestamp in milliseconds since the beginning of this replay
     */
    public void sendPacketsTill(int timestamp) {
        Preconditions.checkState(!asyncMode, "This method cannot be used in async mode. Use jumpToTime(int) instead.");
        try {
            while (ctx == null && !terminate) { // Make sure channel is ready
                Thread.sleep(10);
            }

            synchronized (this) {
                if (timestamp == lastTimeStamp) { // Do nothing if we're already there
                    return;
                }
                if (timestamp < lastTimeStamp) { // Restart the replay if we need to go backwards in time
                    hasWorldLoaded = false;
                    lastTimeStamp = 0;
                    if (dis != null) {
                        dis.close();
                        dis = null;
                    }
                    startFromBeginning = false;
                    nextPacket = null;
                    replayHandler.restartedReplay();
                }

                if (dis == null) {
                    dis = new DataInputStream(replayFile.getPacketData());
                }

                while (true) { // Send packets
                    try {
                        PacketData pd;
                        if (nextPacket != null) {
                            // If there is still a packet left from before, use it first
                            pd = nextPacket;
                            nextPacket = null;
                        } else {
                            // Otherwise read one from the input stream
                            pd = new PacketData(dis);
                        }

                        int nextTimeStamp = pd.timestamp;
                        if (nextTimeStamp > timestamp) {
                            // We are done sending all packets
                            nextPacket = pd;
                            break;
                        }

                        // Process packet
                        channelRead(ctx, pd.bytes);
                    } catch (EOFException eof) {
                        // Shit! We hit the end before finishing our job! What shall we do now?
                        // well, let's just pretend we're done...
                        dis = null;
                        break;
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

                // This might be required if we change to async mode anytime soon
                lastPacketSent = System.currentTimeMillis();
                lastTimeStamp = timestamp;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    protected Packet processPacketSync(Packet p) {
        return p; // During synchronous playback everything is sent normally
    }

    /**
     * This is necessary to convert packets from old replays to new replays.
     * @param packet The packet to be transformed.
     * @see #LEGACY_ENTITY_ID
     */
    private void convertLegacyEntityIds(Packet packet) {
        if (packet instanceof S0CPacketSpawnPlayer) {
            S0CPacketSpawnPlayer p = (S0CPacketSpawnPlayer) packet;
            if (p.field_148957_a == LEGACY_ENTITY_ID) {
                p.field_148957_a = actualID;
            }
        } else if (packet instanceof S18PacketEntityTeleport) {
            S18PacketEntityTeleport p = (S18PacketEntityTeleport) packet;
            if (p.field_149458_a == LEGACY_ENTITY_ID) {
                p.field_149458_a = actualID;
            }
        } else if (packet instanceof S14PacketEntity.S17PacketEntityLookMove) {
            S14PacketEntity.S17PacketEntityLookMove p = (S14PacketEntity.S17PacketEntityLookMove) packet;
            if (p.field_149074_a == LEGACY_ENTITY_ID) {
                p.field_149074_a = actualID;
            }
        } else if (packet instanceof S19PacketEntityHeadLook) {
            S19PacketEntityHeadLook p = (S19PacketEntityHeadLook) packet;
            if (p.field_149384_a == LEGACY_ENTITY_ID) {
                p.field_149384_a = actualID;
            }
        } else if (packet instanceof S12PacketEntityVelocity) {
            S12PacketEntityVelocity p = (S12PacketEntityVelocity) packet;
            if (p.field_149417_a == LEGACY_ENTITY_ID) {
                p.field_149417_a = actualID;
            }
        } else if (packet instanceof S0BPacketAnimation) {
            S0BPacketAnimation p = (S0BPacketAnimation) packet;
            if (p.entityId == LEGACY_ENTITY_ID) {
                p.entityId = actualID;
            }
        } else if (packet instanceof S04PacketEntityEquipment) {
            S04PacketEntityEquipment p = (S04PacketEntityEquipment) packet;
            if (p.field_149394_a == LEGACY_ENTITY_ID) {
                p.field_149394_a = actualID;
            }
        } else if (packet instanceof S1BPacketEntityAttach) {
            S1BPacketEntityAttach p = (S1BPacketEntityAttach) packet;
            if (p.field_149408_a == LEGACY_ENTITY_ID) {
                p.field_149408_a = actualID;
            }
            if (p.field_149406_b == LEGACY_ENTITY_ID) {
                p.field_149406_b = actualID;
            }
        } else if (packet instanceof S0DPacketCollectItem) {
            S0DPacketCollectItem p = (S0DPacketCollectItem) packet;
            if (p.field_149356_b == LEGACY_ENTITY_ID) {
                p.field_149356_b = actualID;
            }
        } else if (packet instanceof S13PacketDestroyEntities) {
            S13PacketDestroyEntities p = (S13PacketDestroyEntities) packet;
            if (p.field_149100_a.length == 1 && p.field_149100_a[0] == LEGACY_ENTITY_ID) {
                p.field_149100_a[0] = actualID;
            }
        }
    }

    private static final class PacketData {
        private final int timestamp;
        private final byte[] bytes;

        public PacketData(DataInputStream in) throws IOException {
            timestamp = in.readInt();
            bytes = new byte[in.readInt()];
            in.readFully(bytes);
        }
    }
}
