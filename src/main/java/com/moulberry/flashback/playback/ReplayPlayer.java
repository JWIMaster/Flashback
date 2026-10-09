package com.moulberry.flashback.playback;

import com.mojang.authlib.GameProfile;
import com.moulberry.flashback.ext.ServerLevelExt;
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.biome.BiomeManager;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public class ReplayPlayer extends ServerPlayer {
    public boolean followLocalPlayerNextTick = false;

    /**
     * The player this viewer is meant to be watching, or null to watch the replay's own viewpoint.
     *
     * <p>This is the authoritative record of intent: the camera entity itself is only the current
     * resolution of it. Keeping the intent separate matters because a replay destroys and recreates
     * entities as it streams, so the entity the camera points at is routinely discarded while the
     * player being watched has not changed.
     */
    public UUID spectateTarget = null;
    public int forceRespectateTickCount = 0;

    public UUID lastFirstPersonDataUUID = null;
    /** Ticks left to keep re-sending the first-person state, so a dropped payload heals. */
    public int resendFirstPersonTicks = 0;
    public int lastFirstPersonSelectedSlot = -1;
    public ItemStack[] lastFirstPersonHotbarItems = new ItemStack[9];
    public float lastFirstPersonExperienceProgress = 0.0f;
    public int lastFirstPersonTotalExperience = 0;
    public int lastFirstPersonExperienceLevel = 0;
    public int lastFirstPersonFoodLevel = 0;
    public float lastFirstPersonSaturationLevel = 0;

    public ReplayPlayer(MinecraftServer minecraftServer, ServerLevel serverLevel, GameProfile gameProfile, ClientInformation clientInformation) {
        super(minecraftServer, serverLevel, gameProfile, clientInformation);
    }

    @Override
    public CommonPlayerSpawnInfo createCommonSpawnInfo(ServerLevel serverLevel) {
        return new CommonPlayerSpawnInfo(serverLevel.dimensionTypeRegistration(), serverLevel.dimension(),
            ((ServerLevelExt)serverLevel).flashback$getSeedHash(), this.gameMode.getGameModeForPlayer(),
            Optional.ofNullable(this.gameMode.getPreviousGameModeForPlayer()),
            serverLevel.isDebug(), serverLevel.isFlat(), this.getLastDeathLocation(), this.getPortalCooldown(), serverLevel.getSeaLevel());
    }

    @Override
    public void setCamera(@Nullable Entity entity) {
        super.setCamera(entity);
    }

    /**
     * Points the camera at the entity currently representing {@link #spectateTarget}, or back at
     * this viewer when there is no target or its entity is not in the level.
     *
     * <p>Called every server tick, so this is what repairs the camera after the replay destroys and
     * recreates the watched entity: the target is remembered as a UUID, and the camera entity is
     * only ever a resolution of it.
     */
    public void syncCameraToSpectateTarget() {
        Entity resolved = null;
        if (this.spectateTarget != null && this.level() != null) {
            Entity entity = this.level().getEntity(this.spectateTarget);
            if (entity != null && !entity.isRemoved() && entity != this) {
                resolved = entity;
            }
        }

        Entity wanted = resolved == null ? this : resolved;
        if (this.getCamera() != wanted) {
            super.setCamera(wanted);
            if (resolved != null && this.forceRespectateTickCount == 0) {
                // Ask the client to re-establish its own camera over the next few ticks.
                this.forceRespectateTickCount = 5;
            }
        }
    }

    /** Stops following anyone and points the camera back at this viewer. */
    public void clearSpectateTarget() {
        this.spectateTarget = null;
        this.forceRespectateTickCount = 0;
        if (this.getCamera() != this) {
            super.setCamera(this);
        }
    }

    @Override
    public int awardRecipes(Collection<RecipeHolder<?>> collection) {
        return 0;
    }

    @Override
    public void awardStat(Stat<?> stat, int i) {
    }

    @Override
    public void resetStat(Stat<?> stat) {
    }

    @Override
    public void indicateDamage(double d, double e) {
    }

    @Override
    public void handleDamageEvent(DamageSource damageSource) {
    }

    @Override
    public boolean hurtClient(DamageSource damageSource) {
        return false;
    }

    @Override
    public boolean hurtServer(ServerLevel serverLevel, DamageSource damageSource, float f) {
        return false;
    }

    @Override
    public boolean isInvulnerableTo(ServerLevel serverLevel, DamageSource damageSource) {
        return true;
    }

}
