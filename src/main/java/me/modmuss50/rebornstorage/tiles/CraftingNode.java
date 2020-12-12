package me.modmuss50.rebornstorage.tiles;

import com.raoulvdberge.refinedstorage.api.autocrafting.ICraftingPattern;
import com.raoulvdberge.refinedstorage.api.autocrafting.ICraftingPatternContainer;
import com.raoulvdberge.refinedstorage.api.autocrafting.ICraftingPatternProvider;
import com.raoulvdberge.refinedstorage.api.network.INetwork;
import com.raoulvdberge.refinedstorage.api.network.node.INetworkNode;
import com.raoulvdberge.refinedstorage.apiimpl.API;
import com.raoulvdberge.refinedstorage.inventory.item.ItemHandlerBase;
import com.raoulvdberge.refinedstorage.inventory.listener.ListenerNetworkNode;
import com.raoulvdberge.refinedstorage.util.StackUtils;
import me.modmuss50.rebornstorage.RebornStorage;
import me.modmuss50.rebornstorage.RebornStorageEventHandler;
import me.modmuss50.rebornstorage.blocks.BlockMultiCrafter;
import me.modmuss50.rebornstorage.lib.ModInfo;
import me.modmuss50.rebornstorage.multiblocks.MultiBlockCrafter;
import net.minecraft.block.state.IBlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import reborncore.RebornCore;
import reborncore.common.registration.RebornRegistry;
import reborncore.common.registration.impl.ConfigRegistry;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

@RebornRegistry(modID = ModInfo.MOD_ID)
public class CraftingNode implements INetworkNode, ICraftingPatternContainer {

    World world;
    BlockPos pos;
    List<ICraftingPattern> actualPatterns = new ArrayList<>();
    @Nullable
    INetwork network;
    int ticks = 0;
    private UUID uuid;
    private boolean needsRebuild = false;
    private boolean isValid;
    private int speed = -1;
    @ConfigRegistry(comment = "This is the crafting speed of the cpus, the higher the number the more crafting cpus will be needed to achieve greater speeds")
    public static int craftingSpeed = 15;

    @ConfigRegistry(comment = "The number of seconds between a pattern rebuild when inserting items, a higher number will reduce the load on the server, but patterns may take longer to show")
    public static int invUpdateTime = 5;

    private int maxCraftingUpdates;
    private int craftingUpdatesLeft;
    private int updateInterval = 10;
    private int energyUsage = -1;

    private final String variant;

    public void invalidate() {
        isValid = false;
    }


    // An item handler that caches the first available and last used slots.
    public abstract class CachingItemHandler extends ItemHandlerBase {
        private int firstAvailable = 0;
        private int lastUsed = -1;
        protected CraftingNode craftingNode;

        protected HashMap<Integer, ICraftingPattern> craftingPatternMap = new HashMap<>();

        public CachingItemHandler(CraftingNode craftingNode, int size, @Nullable Consumer<Integer> listener, Predicate<ItemStack>... validators) {
            super(size, listener, validators);
            this.craftingNode = craftingNode;
        }

        @Override
        protected void onLoad() {
            super.onLoad();
            firstAvailable = getSlots();
            lastUsed = -1;
            for (int i = 0; i < getSlots(); i++) {
                if (getStackInSlot(i).isEmpty()) {
                    firstAvailable = Integer.min(firstAvailable, i);
                } else {
                    lastUsed = Integer.max(lastUsed, i);
                }
            }
        }

        @Override
        protected void onContentsChanged(int slot) {
            super.onContentsChanged(slot);
            for (int i = slot; i < firstAvailable && i >= 0 && getStackInSlot(i).isEmpty(); i--) {
                firstAvailable = i;
            }
            for (int i = slot; i == firstAvailable && i < getSlots() && !getStackInSlot(i).isEmpty(); i++) {
                firstAvailable = i + 1;
            }
            for (int i = slot; i > lastUsed && i < getSlots() && !getStackInSlot(i).isEmpty(); i++) {
                lastUsed = i;
            }
            for (int i = slot; i == lastUsed && i >= 0 && getStackInSlot(i).isEmpty(); i--) {
                lastUsed = i - 1;
            }
            craftingPatternMap.remove(slot); //When the slot changes un cache it
        }

        public int getFirstAvailable() {
            return firstAvailable;
        }

        public int getLastUsed() {
            return lastUsed;
        }

        public boolean isEmpty() {
            return lastUsed == -1;
        }

        public boolean isFull() {
            return firstAvailable == getSlots();
        }
    }

    public CachingItemHandler patterns = new CachingItemHandler(this, 6 * 13, new ListenerNetworkNode(this), s -> s.getItem() instanceof ICraftingPatternProvider && ((ICraftingPatternProvider) s.getItem()).create(world, s).isValid()) {
        @Override
        protected void onContentsChanged(int slot) {
            super.onContentsChanged(slot);
            markDirty();
            needsRebuild = true;
        }

        @Override
        public int getSlotLimit(int slot) {
            return 1;
        }
    };

    public CraftingNode(World world, BlockPos pos, String variant) {
        this.world = world;
        this.pos = pos;

        if (variant == null || variant.isEmpty()) {
            IBlockState state = world.getBlockState(pos);
            if (!(state.getBlock() instanceof BlockMultiCrafter))
                variant = "unknown";
            else
                variant = state.getValue(BlockMultiCrafter.VARIANTS);
        }

        this.variant = variant;
    }

    public void rebuildPatterns(String reason) {
        this.actualPatterns.clear();
        if (!world.isRemote && isValidMultiBlock(true)) {
            if (!patterns.isEmpty()) {
                for (int i = 0; i < patterns.getSlots(); i++) {
                    ItemStack stack = patterns.getStackInSlot(i);
                    if (!stack.isEmpty() && stack.getItem() instanceof ICraftingPatternProvider) {
                        if (patterns.craftingPatternMap.containsKey(i)) {
                            actualPatterns.add(patterns.craftingPatternMap.get(i));
                        } else {
                            ICraftingPattern pattern = ((ICraftingPatternProvider) stack.getItem()).create(world, stack);
                            if (pattern.isValid()) {
                                actualPatterns.add(pattern);
                                patterns.craftingPatternMap.put(i, pattern);
                            }
                        }
                    } else {
                        patterns.craftingPatternMap.remove(i);
                    }
                }
            } else {
                patterns.craftingPatternMap.clear();
            }
        }

        if (getNetwork() != null) {
            RebornStorageEventHandler.queue(network.getCraftingManager(), this, reason);
        }
    }

    protected void stateChange(INetwork network, boolean state, String reason) {
        if (!state) {
            actualPatterns.clear();
        }
        RebornStorageEventHandler.queue(network.getCraftingManager(), this, reason);
    }

    @Nullable
    public TileMultiCrafter getTile() {
        TileEntity tileEntity = world.getTileEntity(pos);
        if (tileEntity instanceof TileMultiCrafter) {
            return (TileMultiCrafter) tileEntity;
        }
        RebornCore.logHelper.debug(tileEntity + " is not an instance of TileMultiCrafter, this is an error and your RebornStorage multiblock may not work. Please report to the mod author " + pos);
        return null;
    }

    public boolean isValidMultiBlock(boolean check) {

        if (!check && isValid) {
            return true;
        }
        TileMultiCrafter tileMultiCrafter = getTile();
        if (tileMultiCrafter == null) {
            return false;
        }
        MultiBlockCrafter multiBlockCrafter = getTile().getMultiBlock();
        if (multiBlockCrafter == null) {
            return false;
        }
        isValid = multiBlockCrafter.isAssembled();

        return isValid;

    }

    @Override
    public int getEnergyUsage() {
        if (this.energyUsage == -1) {
            switch (this.variant) {
                case "frame":
                    this.energyUsage = 1;
                    break;
                case "cpu":
                case "io":
                case "heat":
                    this.energyUsage = 0;
                    break;
                default:
                    this.energyUsage = 50;
                    break;
            }
        }

        return this.energyUsage;
    }

    @Nonnull
    @Override
    public ItemStack getItemStack() {
        return getTile().getStack();
    }

    @Override
    public void onConnected(INetwork iNetwork) {
        this.network = iNetwork;
        stateChange(network, true, "connected to network");
        rebuildPatterns("connected to network");
    }

    @Override
    public void onDisconnected(INetwork iNetwork) {
        this.network = null;
        actualPatterns.clear();
        stateChange(iNetwork, true, "disconnected from network");
    }

    @Override
    public boolean canUpdate() {
        if (network != null) {
            return network.canRun();
        }
        return false;
    }

    @Nullable
    @Override
    public INetwork getNetwork() {
        return network;
    }

    @Override
    public void update() {
        ticks++;
        if (ticks == 1) {
            rebuildPatterns("first tick rebuild");
        }

        if (ticks % 5 == 0) {
            updateUpdateInterval();
            updateMaxCraftingUpdates();
        }

        if (this.updateInterval != 0 && ticks % this.updateInterval == 0) {
            this.craftingUpdatesLeft = this.maxCraftingUpdates;
        }

        if (needsRebuild && world.getTotalWorldTime() % (invUpdateTime * 20) == 0) {
            rebuildPatterns("inv slot change");
            needsRebuild = false;
        }
    }

    @Override
    public boolean isTickable() {
        return variant.equals("storage");
    }

    @Override
    public void useCraftingUpdates(int updates) {
        this.craftingUpdatesLeft = Math.max(this.craftingUpdatesLeft - updates, 0);
    }

    @Override
    public int getUpdateInterval() {
        return this.updateInterval;
    }

    @Override
    public int getCraftingUpdatesLeft() {
        return this.craftingUpdatesLeft;
    }

    @Override
    public NBTTagCompound write(NBTTagCompound nbtTagCompound) {
        StackUtils.writeItems(patterns, 0, nbtTagCompound);
        return nbtTagCompound;
    }

    @Override
    public BlockPos getPos() {
        return pos;
    }

    @Override
    public World getWorld() {
        return world;
    }

    @Override
    public void markDirty() {
        if (!world.isRemote) {
            API.instance().getNetworkNodeManager(world).markForSaving();
        }
    }

    @Override
    public String getId() {
        return RebornStorage.MULTI_BLOCK_ID;
    }

    public void updateUpdateInterval() {
        this.updateInterval = Math.max(craftingSpeed - getCraftingCpus(), 1);
    }

    public void updateMaxCraftingUpdates() {
        //Dont do anything if we have less cpus than the crafting speed
        if (getCraftingCpus() < craftingSpeed) {
            this.maxCraftingUpdates = 1;
        }
        this.maxCraftingUpdates = Math.max(getCraftingCpus() / Math.max(craftingSpeed, 1), 1);
    }

    public int getCraftingCpus() {
        if (isValid && speed != -1) {
            return speed;
        }
        if (!isValidMultiBlock(false)) {
            return 0;
        }
        TileMultiCrafter tile = getTile();
        if (tile == null)
            return 0;
        MultiBlockCrafter multiBlock = tile.getMultiBlock();
        if (multiBlock == null)
            return 0;
        speed = multiBlock.speed;
        return speed;
    }

    @Override
    public IItemHandler getConnectedInventory() {
        return null;
    }

    @Nullable
    @Override
    public IFluidHandler getConnectedFluidInventory() {
        return null;
    }

    @Override
    public TileEntity getConnectedTile() {
        return null;
    }

    @Override
    public TileEntity getFacingTile() {
        return null;
    }

    @Override
    public EnumFacing getDirection() {
        return null;
    }

    @Override
    public List<ICraftingPattern> getPatterns() {
        return actualPatterns;
    }

    @Override
    public IItemHandlerModifiable getPatternInventory() {
        if (isValidMultiBlock(true) && getTile().getVarient() != null && getTile().getVarient().equals("storage")) {
            return patterns;
        }
        return null;
    }

    @Override
    public String getName() {
        return "MultiBlock Crafter";
    }

    @Override
    public BlockPos getPosition() {
        return pos;
    }

    @Nullable
    @Override
    public ICraftingPatternContainer getRootContainer() {
        return null;
    }

    @Override
    public UUID getUuid() {
        if (uuid == null) {
            uuid = UUID.randomUUID();
        }
        return uuid;
    }

}
