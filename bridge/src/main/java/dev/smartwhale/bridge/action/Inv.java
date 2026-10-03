package dev.smartwhale.bridge.action;

import dev.smartwhale.bridge.rpc.RpcException;
import dev.smartwhale.bridge.util.Game;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

/** Player inventory helpers that go through {@code InventoryMenu} clicks, like a real player. */
public final class Inv {
    public static final int OFFHAND = 40;

    private Inv() {
    }

    /** {@link Inventory} index → {@code InventoryMenu} slot index. */
    public static int menuSlot(int invIndex) {
        if (invIndex < 9) return 36 + invIndex;
        if (invIndex < 36) return invIndex;
        if (invIndex < 40) return 8 - (invIndex - 36);
        if (invIndex == OFFHAND) return 45;
        throw new IllegalArgumentException("bad inventory index " + invIndex);
    }

    public static void requireInventoryMenu(LocalPlayer p) {
        if (p.containerMenu != p.inventoryMenu) {
            throw new RpcException(RpcException.NO_MENU, "A container screen is open",
                    "Call menu.close first, or use menu.* to move items inside the open screen");
        }
    }

    public static void click(LocalPlayer p, int menuSlot, int button, ClickType type) {
        Game.gameMode().handleInventoryMouseClick(p.inventoryMenu.containerId, menuSlot, button, type, p);
    }

    /** First matching slot: hotbar, then main inventory, then offhand. -1 if none. */
    public static int find(LocalPlayer p, Predicate<ItemStack> match) {
        Inventory inv = p.getInventory();
        if (match.test(inv.getItem(inv.selected))) return inv.selected;
        for (int i = 0; i < 36; i++) if (match.test(inv.getItem(i))) return i;
        if (match.test(inv.getItem(OFFHAND))) return OFFHAND;
        return -1;
    }

    /** Puts the stack at {@code invIndex} into the main hand: selects it if on the hotbar, else swaps it into the selected slot. */
    public static void toMainHand(LocalPlayer p, int invIndex) {
        Inventory inv = p.getInventory();
        if (invIndex == inv.selected) return;
        if (invIndex < 9) {
            inv.selected = invIndex;
            return;
        }
        requireInventoryMenu(p);
        click(p, menuSlot(invIndex), inv.selected, ClickType.SWAP);
    }
}
