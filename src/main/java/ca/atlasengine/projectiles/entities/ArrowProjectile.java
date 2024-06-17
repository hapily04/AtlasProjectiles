package ca.atlasengine.projectiles.entities;

import ca.atlasengine.projectiles.AbstractProjectile;
import net.minestom.server.MinecraftServer;
import net.minestom.server.ServerFlag;
import net.minestom.server.collision.Aerodynamics;
import net.minestom.server.collision.BoundingBox;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.metadata.projectile.ProjectileMeta;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.entity.EntityShootEvent;
import net.minestom.server.event.entity.projectile.ProjectileCollideWithEntityEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockHandler;
import org.jetbrains.annotations.NotNull;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

// https://gist.github.com/hapily04/a463cbed41d2cfba04a58ecc62fa61f9
public class ArrowProjectile extends AbstractProjectile {
    private static final BoundingBox SMALL_BOUNDING_BOX = new BoundingBox(0.01, 0.01, 0.01);

    private boolean critical = false;
    private boolean firstTick = true;
    private boolean inBlock = false;

    public boolean isCritical() {
        return critical;
    }

    public void setCritical(boolean critical) {
        this.critical = critical;
    }

    public ArrowProjectile(EntityType type, Entity shooter) {
        super(type, shooter);
        setup();
        this.setBoundingBox(SMALL_BOUNDING_BOX);
    }

    private void setup() {
        this.hasCollision = false;
        if (getEntityMeta() instanceof ProjectileMeta projectileMeta) {
            projectileMeta.setShooter(this.shooter);
        }
    }

    @Override
    public void tick(long time) {
        if (removed || inBlock) return;

        final Pos posBefore = getPosition();
        updatePosition(time);
        final Pos posNow = getPosition();

        Vec diff = Vec.fromPoint(posNow.sub(posBefore));
        float yaw = (float) Math.toDegrees(Math.atan2(diff.x(), diff.z()));
        float pitch = (float) Math.toDegrees(Math.atan2(diff.y(), Math.sqrt(diff.x() * diff.x() + diff.z() * diff.z())));
        this.position = posNow.withView(yaw, pitch);

        if (firstTick) {
            firstTick = false;
            setView(yaw, pitch);
        }

        checkEntityCollision(posBefore, posNow);
    }

    public void shoot(@NotNull Point from, @NotNull Point to, double power, double spread) {
        var instance = shooter.getInstance();
        if (instance == null) return;

        float yaw = -shooter.getPosition().yaw();
        float originalPitch = -shooter.getPosition().pitch();
        float pitch = originalPitch - 35f;

        double pitchDiff = pitch - 45;
        if (pitchDiff == 0) pitchDiff = 0.0001;
        double pitchAdjust = pitchDiff * 0.002145329238474369D;

        double dx = to.x() - from.x();
        double dy = to.y() - from.y() + pitchAdjust;
        double dz = to.z() - from.z();
        if (!hasNoGravity()) {
            final double xzLength = Math.sqrt(dx * dx + dz * dz);
            dy += xzLength * 0.20000000298023224D;
        }

        final double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        dx /= length;
        dy /= length;
        dz /= length;
        Random random = ThreadLocalRandom.current();
        spread *= 0.007499999832361937D;
        dx += random.nextGaussian() * spread;
        dy += random.nextGaussian() * spread;
        dz += random.nextGaussian() * spread;

        final EntityShootEvent shootEvent = new EntityShootEvent(this.shooter, this, from, power, spread);
        EventDispatcher.call(shootEvent);
        if (shootEvent.isCancelled()) {
            remove();
            return;
        }

        final double mul = ServerFlag.SERVER_TICKS_PER_SECOND * power;
        Vec v = new Vec(dx * mul, dy * mul * 0.9, dz * mul);

        this.setInstance(instance, new Pos(from.x(), from.y(), from.z(), yaw, originalPitch)).whenComplete((result, throwable) -> {
            if (throwable != null) {
                throwable.printStackTrace();
            } else {
                synchronizePosition(); // initial synchronization, required to be 100% precise
                setVelocity(v);
            }
        });
    }

    @Override
    protected void handleBlockCollision(Block hitBlock, Point hitPos, Pos posBefore) {
        velocity = Vec.ZERO;
        setNoGravity(true);

        inBlock = true;
        velocity = Vec.fromPoint(hitPos.sub(posBefore));
        Vec v = velocity.normalize().mul(0.01f); // required so the entity is lit (just outside the block)

        // if the value is zero, it will be unlit. If the value is more than 0.01, there will be noticeable pitch change visually
        position = new Pos(hitPos.x()-v.x(), hitPos.y()-v.y(), hitPos.z()-v.z(), posBefore.yaw(), posBefore.pitch());
        MinecraftServer.getSchedulerManager().scheduleNextTick(this::synchronizePosition); // required as in rare situations there will be a slight disagreement with the client and server on if it hit or not | also scheduling next tick so it doesn't jump to the hit position until it has actually hit
        BlockHandler blockHandler = hitBlock.handler();
        if (blockHandler == null) return;
        blockHandler.onTouch(new BlockHandler.Touch(hitBlock, instance, hitPos, this));
    }

    @Override
    protected void handleEntityCollision(Entity hitEntity, Point hitPos, Pos posBefore) {
        ProjectileCollideWithEntityEvent e = new ProjectileCollideWithEntityEvent(this, Pos.fromPoint(hitPos), hitEntity);
        MinecraftServer.getGlobalEventHandler().call(e);
        if (!e.isCancelled()) {
            remove();
        }
    }

    protected @NotNull Vec updateVelocity(@NotNull Pos entityPosition, @NotNull Vec currentVelocity, @NotNull Block.@NotNull Getter blockGetter, @NotNull Aerodynamics aerodynamics, boolean positionChanged, boolean entityFlying, boolean entityOnGround, boolean entityNoGravity) {
        if (!positionChanged) {
            return entityFlying ? Vec.ZERO : new Vec(0.0, entityNoGravity ? 0.0 : -aerodynamics.gravity() * aerodynamics.verticalAirResistance(), 0.0);
        } else {
            double drag = entityOnGround ? blockGetter.getBlock(entityPosition.sub(0.0, 0.5000001, 0.0)).registry().friction() * aerodynamics.horizontalAirResistance() : aerodynamics.horizontalAirResistance();
            double gravity = entityFlying ? 0.0 : aerodynamics.gravity();
            double gravityDrag = entityFlying ? 0.6 : aerodynamics.verticalAirResistance();
            double x = currentVelocity.x() * drag;
            double y = entityNoGravity ? currentVelocity.y() : (currentVelocity.y() - gravity) * gravityDrag;
            double z = currentVelocity.z() * drag;
            return new Vec(Math.abs(x) < 1.0E-6 ? 0.0 : x, Math.abs(y) < 1.0E-6 ? 0.0 : y, Math.abs(z) < 1.0E-6 ? 0.0 : z);
        }
    }
}
