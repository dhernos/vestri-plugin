package com.vestri.dynamic.bukkit;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.Cancellable;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

class PreStartGuardTest {
    private final UUID member = UUID.randomUUID();
    private final Player player = stub(Player.class, method -> method.equals("getUniqueId") ? member : null);
    private final World world = stub(World.class, method -> null);

    @Test
    void freezesCoordinatesButAllowsLookingAndIntentionalTeleports() {
        var guard = new PreStartGuard(member::equals);
        var from = new Location(world, 1, 2, 3);
        for (var to : new Location[]{new Location(world, 2, 2, 3), new Location(world, 1, 3, 3),
                new Location(world, 1, 2, 4), new Location(stub(World.class, method -> null), 1, 2, 3)}) {
            var move = new PlayerMoveEvent(player, from, to);
            guard.onMove(move);
            assertTrue(move.isCancelled());
        }
        var look = new PlayerMoveEvent(player, from, new Location(world, 1, 2, 3, 90, 45));
        guard.onMove(look);
        assertFalse(look.isCancelled());
        var teleport = new PlayerTeleportEvent(player, from, new Location(world, 10, 20, 30),
                PlayerTeleportEvent.TeleportCause.PLUGIN);
        guard.onMove(teleport);
        assertFalse(teleport.isCancelled());
    }

    @Test
    void livePredicateReleasesWithoutChangingModesAndDoesNotAffectOtherPlayers() {
        AtomicBoolean locked = new AtomicBoolean(true);
        var guard = new PreStartGuard(uuid -> locked.get() && uuid.equals(member));
        var from = new Location(world, 1, 2, 3);
        var to = new Location(world, 4, 5, 6);
        var move = new PlayerMoveEvent(player, from, to);
        guard.onMove(move);
        assertTrue(move.isCancelled());
        locked.set(false);
        var released = new PlayerMoveEvent(player, from, to);
        guard.onMove(released);
        assertFalse(released.isCancelled());
        locked.set(true);
        var other = stub(Player.class, method -> method.equals("getUniqueId") ? UUID.randomUUID() : null);
        var outsider = new PlayerMoveEvent(other, from, to);
        guard.onMove(outsider);
        assertFalse(outsider.isCancelled());
    }

    static <Type> Type stub(Class<Type> type, Function<String, Object> values) {
        return org.mockito.Mockito.mock(type, invocation -> {
            Object value = values.apply(invocation.getMethod().getName());
            return value != null ? value : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
    }

    @Test
    void blocksDamageInBothDirectionsIncludingProjectilesAndEnvironmentalDamage() {
        AtomicBoolean locked = new AtomicBoolean(true);
        var guard = new PreStartGuard(uuid -> locked.get() && uuid.equals(member));
        var other = stub(Player.class, method -> method.equals("getUniqueId") ? UUID.randomUUID() : null);
        var mob = stub(Entity.class, method -> null);
        var projectile = stub(Projectile.class, method -> method.equals("getShooter") ? player : null);
        var otherProjectile = stub(Projectile.class, method -> method.equals("getShooter") ? other : null);
        var source = stub(DamageSource.class, method -> null);
        var credited = stub(DamageSource.class, method -> method.equals("getCausingEntity") ? player : null);
        for (boolean active : List.of(true, false)) {
            locked.set(active);
            List<EntityDamageEvent> protectedEvents = List.of(
                    new EntityDamageEvent(player, EntityDamageEvent.DamageCause.FALL, source, 2),
                    new EntityDamageByEntityEvent(mob, player, EntityDamageEvent.DamageCause.ENTITY_ATTACK, source, 2),
                    new EntityDamageByEntityEvent(otherProjectile, player, EntityDamageEvent.DamageCause.PROJECTILE, source, 2),
                    new EntityDamageByEntityEvent(player, other, EntityDamageEvent.DamageCause.ENTITY_ATTACK, source, 2),
                    new EntityDamageByEntityEvent(player, mob, EntityDamageEvent.DamageCause.ENTITY_ATTACK, source, 2),
                    new EntityDamageByEntityEvent(projectile, other, EntityDamageEvent.DamageCause.PROJECTILE, source, 2),
                    new EntityDamageByEntityEvent(projectile, mob, EntityDamageEvent.DamageCause.PROJECTILE, source, 2),
                    new EntityDamageEvent(mob, EntityDamageEvent.DamageCause.ENTITY_EXPLOSION, credited, 2));
            for (var event : protectedEvents) {
                guard.onDamage(event);
                assertEquals(active, event.isCancelled());
            }
        }
        locked.set(true);
        var unrelated = new EntityDamageByEntityEvent(other, mob, EntityDamageEvent.DamageCause.ENTITY_ATTACK, source, 2);
        guard.onDamage(unrelated);
        assertFalse(unrelated.isCancelled());
    }

    @Test
    void cancelsWorldItemsInventoryBucketsArmorStandsAndOtherActionsOnlyWhileFrozen() {
        AtomicBoolean locked = new AtomicBoolean(true);
        var guard = new PreStartGuard(uuid -> locked.get() && uuid.equals(member));
        var block = stub(Block.class, method -> null);
        var state = stub(BlockState.class, method -> null);
        var item = stub(Item.class, method -> null);
        var stand = stub(ArmorStand.class, method -> null);
        var projectile = stub(Projectile.class, method -> method.equals("getShooter") ? player : null);
        var vehicle = stub(Vehicle.class, method -> null);
        var stack = new ItemStack(Material.STONE);
        var view = view(player);
        for (boolean active : List.of(true, false)) {
            locked.set(active);
            List<Cancellable> events = new ArrayList<>();
            var breaking = new BlockBreakEvent(block, player);
            guard.onBreak(breaking); events.add(breaking);
            var placing = new BlockPlaceEvent(block, state, block, stack, player, true, EquipmentSlot.HAND);
            guard.onPlace(placing); events.add(placing);
            var interact = new PlayerInteractEvent(player, org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK,
                    stack, block, BlockFace.UP);
            guard.onInteract(interact); events.add(interact);
            var entity = new PlayerInteractEntityEvent(player, stand);
            guard.onEntityInteract(entity); events.add(entity);
            var at = new PlayerInteractAtEntityEvent(player, stand, new Vector());
            guard.onEntityInteractAt(at); events.add(at);
            var armor = new PlayerArmorStandManipulateEvent(player, stand, stack, stack, EquipmentSlot.HAND);
            guard.onArmorStand(armor); events.add(armor);
            var drop = new PlayerDropItemEvent(player, item);
            guard.onDrop(drop); events.add(drop);
            var pickup = new EntityPickupItemEvent(player, item, 0);
            guard.onPickup(pickup); events.add(pickup);
            var click = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT,
                    InventoryAction.PICKUP_ALL);
            guard.onInventoryClick(click); events.add(click);
            var drag = new InventoryDragEvent(view, stack, stack, false, Map.of(0, stack));
            guard.onInventoryDrag(drag); events.add(drag);
            var open = new InventoryOpenEvent(view);
            guard.onInventoryOpen(open); events.add(open);
            var empty = new PlayerBucketEmptyEvent(player, block, block, BlockFace.UP, Material.WATER_BUCKET, stack);
            guard.onBucketEmpty(empty); events.add(empty);
            var fill = new PlayerBucketFillEvent(player, block, block, BlockFace.UP, Material.BUCKET, stack);
            guard.onBucketFill(fill); events.add(fill);
            var capture = new PlayerBucketEntityEvent(player, stand, stack, stack, EquipmentSlot.HAND);
            guard.onBucketEntity(capture); events.add(capture);
            var leash = new PlayerLeashEntityEvent(stand, player, player);
            guard.onLeash(leash); events.add(leash);
            var unleash = new PlayerUnleashEntityEvent(stand, player, EquipmentSlot.HAND);
            guard.onUnleash(unleash); events.add(unleash);
            var shear = new PlayerShearEntityEvent(player, stand, stack, EquipmentSlot.HAND);
            guard.onShear(shear); events.add(shear);
            var hanging = new HangingBreakByEntityEvent(stub(Hanging.class, method -> null), projectile);
            guard.onHangingBreak(hanging); events.add(hanging);
            var consume = new PlayerItemConsumeEvent(player, stack);
            guard.onConsume(consume); events.add(consume);
            var swap = new PlayerSwapHandItemsEvent(player, stack, stack);
            guard.onSwap(swap); events.add(swap);
            var launch = new ProjectileLaunchEvent(projectile);
            guard.onProjectile(launch); events.add(launch);
            var bow = new EntityShootBowEvent(player, stack, stack, projectile, EquipmentSlot.HAND, 1, true);
            guard.onBow(bow); events.add(bow);
            var hunger = new FoodLevelChangeEvent(player, 10);
            guard.onHunger(hunger); events.add(hunger);
            var command = new PlayerCommandPreprocessEvent(player, "/tp 1 2 3", Set.of(player));
            guard.onCommand(command); events.add(command);
            var bed = new PlayerBedEnterEvent(player, block, PlayerBedEnterEvent.BedEnterResult.OK);
            guard.onBed(bed); events.add(bed);
            var fish = new PlayerFishEvent(player, null, null, PlayerFishEvent.State.FISHING);
            guard.onFish(fish); events.add(fish);
            var enter = new VehicleEnterEvent(vehicle, player);
            guard.onVehicleEnter(enter); events.add(enter);
            var vehicleDamage = new VehicleDamageEvent(vehicle, projectile, 2);
            guard.onVehicleDamage(vehicleDamage); events.add(vehicleDamage);
            var vehicleDestroy = new VehicleDestroyEvent(vehicle, player);
            guard.onVehicleDestroy(vehicleDestroy); events.add(vehicleDestroy);
            for (var event : events) assertEquals(active, event.isCancelled(), event.getClass().getSimpleName());
        }
    }

    @Test
    void reconnectAndRespawnPlayerObjectsAreStillGuardedByRosterUuid() {
        var guard = new PreStartGuard(member::equals);
        for (int incarnation = 0; incarnation < 3; incarnation++) {
            var replacement = stub(Player.class, method -> method.equals("getUniqueId") ? member : null);
            var move = new PlayerMoveEvent(replacement, new Location(world, 1, 2, 3), new Location(world, 4, 5, 6));
            guard.onMove(move);
            assertTrue(move.isCancelled());
        }
    }

    @Test
    void movingVehicleEvictsFrozenPassengerAndReturnsThemToPreviousPosition() {
        List<String> operations = new ArrayList<>();
        var passenger = stub(Player.class, method -> {
            if (method.equals("getUniqueId")) return member;
            if (method.equals("teleport")) { operations.add("teleport"); return true; }
            return null;
        });
        var vehicle = stub(Vehicle.class, method -> {
            if (method.equals("getPassengers")) return List.of(passenger);
            if (method.equals("removePassenger")) { operations.add("remove"); return true; }
            return null;
        });
        var event = new VehicleMoveEvent(vehicle, new Location(world, 1, 2, 3), new Location(world, 4, 5, 6));
        new PreStartGuard(member::equals).onVehicleMove(event);
        assertEquals(List.of("remove", "teleport"), operations);
        operations.clear();
        new PreStartGuard(uuid -> false).onVehicleMove(event);
        assertTrue(operations.isEmpty());
    }

    private static InventoryView view(Player player) {
        var inventory = stub(Inventory.class, method -> method.equals("getSize") ? 27 : null);
        return new InventoryView() {
            public Inventory getTopInventory() { return inventory; }
            public Inventory getBottomInventory() { return inventory; }
            public Player getPlayer() { return player; }
            public InventoryType getType() { return InventoryType.CHEST; }
            public String getTitle() { return "Inventory"; }
            public String getOriginalTitle() { return "Inventory"; }
            public void setTitle(String title) { }
        };
    }
}