package com.instanthoppers;

import net.minecraft.block.BlockHopper;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityHopper;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;

import java.lang.reflect.Field;
import java.util.List;

@Mod(modid = Main.MOD_ID, version = Main.VERSION, name = Main.NAME)
@Mod.EventBusSubscriber(modid = Main.MOD_ID)
public class Main {
    public static final String MOD_ID = "instant_hoppers";
    public static final String VERSION = "1.0";
    public static final String NAME = "Instant Hoppers";

    private static Field transferCooldownField = null;

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(Main.class);
        try {
            // "field_145903_q" is the obfuscated SRG name for transferCooldown in 1.12.2
            transferCooldownField = ReflectionHelper.findField(TileEntityHopper.class, "transferCooldown", "field_145901_j");
            transferCooldownField.setAccessible(true);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase == TickEvent.Phase.END && !event.world.isRemote && transferCooldownField != null) {
            World world = event.world;
            List<TileEntity> list = world.loadedTileEntityList;
            
            // Loop through loaded TileEntities looking for hoppers
            for (int i = 0; i < list.size(); i++) {
                TileEntity te = list.get(i);
                if (te != null && te.getClass() == TileEntityHopper.class) {
                    TileEntityHopper hopper = (TileEntityHopper) te;
                    if (hopper.isInvalid()) continue;
                    
                    try {
                        int cd = transferCooldownField.getInt(hopper);
                        // A cooldown of 8 triggers right after the vanilla hopper successfully performs a single item transfer
                        // We intercept here to complete the rest of the bulk movement!
                        if (cd == 8) {
                            bulkTransfer(hopper, world);
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                }
            }
        }
    }

    private static void bulkTransfer(TileEntityHopper hopper, World world) {
        boolean changed = false;

        IBlockState state = world.getBlockState(hopper.getPos());
        if (!(state.getBlock() instanceof BlockHopper)) return;
        EnumFacing facing = state.getValue(BlockHopper.FACING);

        IItemHandler hopperExtract = hopper.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, EnumFacing.DOWN);
        IItemHandler hopperInsert = hopper.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, EnumFacing.UP);

        // 1. PUSH to connected target container
        BlockPos targetPos = hopper.getPos().offset(facing);
        TileEntity targetTE = world.getTileEntity(targetPos);
        IItemHandler target = null;
        if (targetTE != null && targetTE.hasCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, facing.getOpposite())) {
            target = targetTE.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, facing.getOpposite());
        }

        if (target != null && hopperExtract != null) {
            // Keep pushing until we can't push anymore
            boolean pushedThisLoop = true;
            while(pushedThisLoop) {
                pushedThisLoop = false;
                for (int i = 0; i < hopperExtract.getSlots(); i++) {
                    ItemStack stackInHopper = hopperExtract.extractItem(i, 64, true);
                    if (!stackInHopper.isEmpty()) {
                        ItemStack remainder = ItemHandlerHelper.insertItemStacked(target, stackInHopper, false);
                        int amountMoved = stackInHopper.getCount() - remainder.getCount();
                        if (amountMoved > 0) {
                            hopperExtract.extractItem(i, amountMoved, false);
                            pushedThisLoop = true;
                            changed = true;
                        }
                    }
                }
            }
        }

        // 2. PULL from connected source container above it
        BlockPos sourcePos = hopper.getPos().up();
        TileEntity sourceTE = world.getTileEntity(sourcePos);
        IItemHandler source = null;
        if (sourceTE != null && sourceTE.hasCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, EnumFacing.DOWN)) {
            source = sourceTE.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, EnumFacing.DOWN);
        }

        if (source != null && hopperInsert != null) {
            // Keep pulling until full or source is empty
            boolean pulledThisLoop = true;
            while(pulledThisLoop) {
                pulledThisLoop = false;
                for (int i = 0; i < source.getSlots(); i++) {
                    ItemStack stackInSource = source.extractItem(i, 64, true);
                    if (!stackInSource.isEmpty()) {
                        ItemStack remainder = ItemHandlerHelper.insertItemStacked(hopperInsert, stackInSource, false);
                        int amountMoved = stackInSource.getCount() - remainder.getCount();
                        if (amountMoved > 0) {
                            source.extractItem(i, amountMoved, false);
                            pulledThisLoop = true;
                            changed = true;
                        }
                    }
                }
            }
        }

        // 3. PULL dropped items lying on top exactly like a hopper normally does, but instantly sweeping all items
        if (hopperInsert != null) {
            List<EntityItem> items = world.getEntitiesWithinAABB(EntityItem.class, new AxisAlignedBB(hopper.getPos().up()));
            for (EntityItem item : items) {
                if (!item.isDead && !item.getItem().isEmpty()) {
                    ItemStack remainder = ItemHandlerHelper.insertItemStacked(hopperInsert, item.getItem().copy(), false);
                    item.setItem(remainder); // Re-assign remainder dropping what wasn't absorbed
                    if (remainder.isEmpty()) {
                        item.setDead();
                    }
                    changed = true;
                }
            }
        }

        if (changed) {
            hopper.markDirty();
            if (targetTE != null) targetTE.markDirty();
            if (sourceTE != null) sourceTE.markDirty();
        }
    }
}