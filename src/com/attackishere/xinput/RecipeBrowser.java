package com.attackishere.xinput;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.ShapedRecipes;
import net.minecraft.item.crafting.ShapelessRecipes;
import net.minecraftforge.oredict.ShapedOreRecipe;
import net.minecraftforge.oredict.ShapelessOreRecipe;
import net.minecraftforge.oredict.OreDictionary;
import org.lwjgl.opengl.GL11;

import java.lang.reflect.Field;
import java.util.*;

public class RecipeBrowser {

    private final Minecraft mc;

    public boolean isOpen = false;
    private List<IRecipe> craftableRecipes = new ArrayList<IRecipe>();
    private int selectedIndex = 0;
    private int scrollOffset = 0;

    // UPDATED CONSTANTS
    private static final int VISIBLE_ROWS = 8;
    private static final int PANEL_W = 120;
    private static final int ROW_H = 16;
    private static final int PANEL_H = VISIBLE_ROWS * ROW_H + 26;
    private static final RenderItem itemRenderer = new RenderItem();
    private static final int MAX_RECURSION_DEPTH = 0;

    private List<IRecipe> allRecipesCache = null;
    private final Map<Long, List<IRecipe>> outputIndex = new HashMap<Long, List<IRecipe>>();
    private List<Slot> lastFoundCraftingSlots = null;

    public RecipeBrowser(Minecraft mc) {
        this.mc = mc;
    }

    public void open() {
        craftableRecipes = scanCraftable();
        selectedIndex = 0;
        scrollOffset = 0;
        isOpen = true;
    }

    public void close() {
        isOpen = false;
    }

    private int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }

    public void scroll(int dir) {
        if (craftableRecipes.isEmpty()) return;
        selectedIndex = clamp(selectedIndex + dir, 0, craftableRecipes.size() - 1);
        if (selectedIndex < scrollOffset) scrollOffset = selectedIndex;
        if (selectedIndex >= scrollOffset + VISIBLE_ROWS) scrollOffset = selectedIndex - VISIBLE_ROWS + 1;
    }

    public boolean confirm() {
        if (craftableRecipes.isEmpty()) return false;
        IRecipe recipe = craftableRecipes.get(selectedIndex);
        boolean ok = fillGrid(recipe);
        if (ok) isOpen = false;
        return ok;
    }

    public void render(int scaledW, int scaledH) {
        if (!isOpen) return;
        FontRenderer fr = mc.fontRenderer;

        int panelX = 4;
        int panelY = 4;

        drawRect(panelX - 2, panelY - 2, panelX + PANEL_W + 2, panelY + PANEL_H + 2, 0xCC000000);
        drawRect(panelX - 1, panelY - 1, panelX + PANEL_W + 1, panelY + PANEL_H + 1, 0xFF444444);

        String header = craftableRecipes.isEmpty() ? "No Recipes" : "Craft (" + craftableRecipes.size() + ")";
        fr.drawStringWithShadow(header, panelX + 2, panelY + 2, 0xFFFFAA00);
        drawRect(panelX, panelY + 11, panelX + PANEL_W, panelY + 12, 0xFF666666);

        int rowY = panelY + 14;
        for (int i = 0; i < VISIBLE_ROWS; i++) {
            int idx = scrollOffset + i;
            if (idx >= craftableRecipes.size()) break;

            IRecipe recipe = craftableRecipes.get(idx);
            ItemStack output = recipe.getRecipeOutput();
            if (output == null) continue;

            boolean selected = (idx == selectedIndex);
            if (selected) {
                drawRect(panelX, rowY - 1, panelX + PANEL_W, rowY + ROW_H - 1, 0x66FFFFFF);
            }

            renderItemStack(output, panelX + 2, rowY - 1);

            String name = output.getDisplayName();
            if (output.stackSize > 1) name = output.stackSize + "x " + name;

            int maxWidth = PANEL_W - 22;
            if (fr.getStringWidth(name) > maxWidth) {
                while (name.length() > 3 && fr.getStringWidth(name + "..") > maxWidth) {
                    name = name.substring(0, name.length() - 1);
                }
                name += "..";
            }

            fr.drawStringWithShadow(name, panelX + 20, rowY + 3, selected ? 0xFFFFFF00 : 0xFFFFFFFF);

            rowY += ROW_H;
        }

        int footerY = panelY + PANEL_H - 10;
        drawRect(panelX, footerY - 2, panelX + PANEL_W, footerY - 1, 0xFF666666);
        fr.drawStringWithShadow("\u2191\u2193 [A]Craft [B]Exit", panelX + 2, footerY, 0xFF888888);
    }

    @SuppressWarnings("unchecked")
    private List<IRecipe> scanCraftable() {
        List<IRecipe> result = new ArrayList<IRecipe>();
        if (mc.thePlayer == null) return result;
        buildRecipeCaches();
        Map<Long, Integer> available = buildInventoryMap();
        for (IRecipe recipe : allRecipesCache) {
            try {
                if (recipe == null || recipe.getRecipeOutput() == null) continue;
                if (canCraftRecursive(recipe, available, MAX_RECURSION_DEPTH)) {
                    result.add(recipe);
                }
            } catch (Throwable ignored) {}
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void buildRecipeCaches() {
        if (allRecipesCache != null) return;
        allRecipesCache = (List<IRecipe>) CraftingManager.getInstance().getRecipeList();
        outputIndex.clear();
        if (allRecipesCache == null) return;
        for (IRecipe r : allRecipesCache) {
            try {
                ItemStack out = r.getRecipeOutput();
                if (out == null) continue;
                long key = itemKeyWildcard(out);
                List<IRecipe> lst = outputIndex.get(key);
                if (lst == null) {
                    lst = new ArrayList<IRecipe>();
                    outputIndex.put(key, lst);
                }
                lst.add(r);
            } catch (Throwable ignored) {}
        }
    }

    private boolean canCraftRecursive(IRecipe recipe, Map<Long, Integer> available, int depth) {
        if (mc.thePlayer == null || depth < 0) return false;
        Map<Long, Integer> tempAvailable = new HashMap<Long, Integer>(available);
        return resolveAndConsumeRecipeIngredients(recipe, tempAvailable, depth, new HashSet<Long>());
    }

    private boolean resolveAndConsumeRecipeIngredients(IRecipe recipe, Map<Long, Integer> tempAvailable, int depth, Set<Long> visitedProductions) {
        try {
            if (recipe instanceof ShapedRecipes) {
                ItemStack[] ingr = getShapedIngredients((ShapedRecipes) recipe);
                if (ingr == null) return false;
                return consumeItemStacks(ingr, tempAvailable, depth, visitedProductions);
            } else if (recipe instanceof ShapelessRecipes) {
                List<ItemStack> ingr = getShapelessIngredients((ShapelessRecipes) recipe);
                if (ingr == null) return false;
                return consumeItemStacks(ingr.toArray(new ItemStack[0]), tempAvailable, depth, visitedProductions);
            } else if (recipe instanceof ShapedOreRecipe) {
                Object[] patt = getShapedOrePatternObjects((ShapedOreRecipe) recipe);
                if (patt == null) return false;
                for (Object needObj : patt) {
                    if (needObj == null) continue;
                    if (!consumeOreIngredient(needObj, tempAvailable, depth, visitedProductions)) return false;
                }
                return true;
            } else if (recipe instanceof ShapelessOreRecipe) {
                List<Object> patt = getShapelessOrePatternObjects((ShapelessOreRecipe) recipe);
                if (patt == null) return false;
                for (Object needObj : patt) {
                    if (needObj == null) continue;
                    if (!consumeOreIngredient(needObj, tempAvailable, depth, visitedProductions)) return false;
                }
                return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean consumeItemStacks(ItemStack[] needs, Map<Long, Integer> tempAvailable, int depth, Set<Long> visitedProductions) {
        Map<Long, Integer> neededCounts = new LinkedHashMap<Long, Integer>();
        for (ItemStack need : needs) {
            if (need == null) continue;
            long k = itemKeyWildcard(need);
            Integer c = neededCounts.get(k);
            neededCounts.put(k, (c == null ? 1 : c + 1));
        }
        for (Map.Entry<Long, Integer> e : neededCounts.entrySet()) {
            ItemStack sample = keyToSampleStack(e.getKey());
            for (int i = 0; i < e.getValue(); i++) {
                if (!resolveOneIngredient(sample, tempAvailable, depth, visitedProductions)) return false;
            }
        }
        return true;
    }

    private boolean consumeOreIngredient(Object needObj, Map<Long, Integer> tempAvailable, int depth, Set<Long> visitedProductions) {
        if (needObj instanceof ItemStack) {
            return resolveOneIngredient((ItemStack) needObj, tempAvailable, depth, visitedProductions);
        } else if (needObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<ItemStack> alts = (List<ItemStack>) needObj;
            for (ItemStack alt : alts) {
                if (consumeAvailable(alt, tempAvailable)) return true;
            }
            if (depth > 0) {
                for (ItemStack alt : alts) {
                    if (resolveOneIngredient(alt, tempAvailable, depth, visitedProductions)) return true;
                }
            }
        } else if (needObj instanceof String) {
            List<ItemStack> alts = OreDictionary.getOres((String) needObj);
            if (alts != null) {
                for (ItemStack alt : alts) {
                    if (consumeAvailable(alt, tempAvailable)) return true;
                }
                if (depth > 0) {
                    for (ItemStack alt : alts) {
                        if (resolveOneIngredient(alt, tempAvailable, depth, visitedProductions)) return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean resolveOneIngredient(ItemStack need, Map<Long, Integer> tempAvailable, int depth, Set<Long> visitedProductions) {
        if (need == null) return true;
        if (consumeAvailable(need, tempAvailable)) return true;
        if (depth <= 0) return false;

        List<IRecipe> candidates = findRecipesProducing(need);
        if (candidates == null || candidates.isEmpty()) return false;

        long needKey = itemKeyWildcard(need);
        if (visitedProductions.contains(needKey)) return false;
        visitedProductions.add(needKey);

        for (IRecipe cand : candidates) {
            Map<Long, Integer> trialAvailable = new HashMap<Long, Integer>(tempAvailable);
            boolean ok = resolveAndConsumeRecipeIngredients(cand, trialAvailable, depth - 1, visitedProductions);
            if (ok) {
                ItemStack produced = cand.getRecipeOutput();
                if (produced != null) addToMap(trialAvailable, itemKey(produced), produced.stackSize);
                if (!consumeAvailable(need, trialAvailable)) continue;
                tempAvailable.clear();
                tempAvailable.putAll(trialAvailable);
                visitedProductions.remove(needKey);
                return true;
            }
        }
        visitedProductions.remove(needKey);
        return false;
    }

    private List<IRecipe> findRecipesProducing(ItemStack need) {
        if (need == null) return Collections.emptyList();
        buildRecipeCaches();
        long wildcardKey = itemKeyWildcard(need);
        List<IRecipe> direct = outputIndex.get(wildcardKey);
        if (direct != null) return direct;

        List<IRecipe> res = new ArrayList<IRecipe>();
        for (IRecipe r : allRecipesCache) {
            try {
                ItemStack out = r.getRecipeOutput();
                if (out == null) continue;
                if (itemMatches(out, need)) res.add(r);
            } catch (Throwable ignored) {}
        }
        return res;
    }

    private Map<Long, Integer> buildInventoryMap() {
        Map<Long, Integer> map = new HashMap<Long, Integer>();
        if (mc.thePlayer == null) return map;
        for (ItemStack stack : mc.thePlayer.inventory.mainInventory) {
            if (stack == null) continue;
            addToMap(map, itemKey(stack), stack.stackSize);
        }
        return map;
    }

    private static void addToMap(Map<Long, Integer> map, long key, int add) {
        Integer cur = map.get(key);
        map.put(key, (cur == null ? 0 : cur) + add);
    }

    private boolean consumeAvailable(ItemStack need, Map<Long, Integer> tempAvailable) {
        if (need == null) return true;
        long exact = itemKey(need);
        Integer c = tempAvailable.get(exact);
        if (c != null && c > 0) {
            tempAvailable.put(exact, c - 1);
            return true;
        }
        if (need.getItemDamage() == 32767 || need.getItemDamage() == -1) {
            int itemID = need.itemID;
            Long chosenKey = null;
            for (Long k : tempAvailable.keySet()) {
                int id = (int) (k >> 16);
                if (id == itemID && tempAvailable.get(k) > 0) {
                    chosenKey = k;
                    break;
                }
            }
            if (chosenKey != null) {
                tempAvailable.put(chosenKey, tempAvailable.get(chosenKey) - 1);
                return true;
            }
        }
        return false;
    }

    private ItemStack resolveOreIngredientPreferPlayer(Object obj) {
        if (obj == null) return null;
        if (obj instanceof ItemStack) return (ItemStack) obj;

        List<ItemStack> alts = null;
        if (obj instanceof java.util.List) {
            @SuppressWarnings("unchecked")
            List<ItemStack> cast = (java.util.List<ItemStack>) obj;
            alts = cast;
        } else if (obj instanceof String) {
            alts = OreDictionary.getOres((String) obj);
        }

        if (alts != null) {
            if (mc.thePlayer != null) {
                for (ItemStack alt : alts) {
                    if (alt == null) continue;
                    if (playerHasItem(alt)) return alt;
                }
            }
            return alts.isEmpty() ? null : alts.get(0);
        }
        return null;
    }

    private boolean playerHasItem(ItemStack need) {
        if (mc.thePlayer == null || need == null) return false;
        for (ItemStack inv : mc.thePlayer.inventory.mainInventory) {
            if (inv == null) continue;
            if (itemMatches(inv, need)) return true;
        }
        return false;
    }

    private long itemKey(ItemStack stack) {
        int dmg = stack.getItem().getHasSubtypes() ? stack.getItemDamage() : 0;
        if (dmg < 0 || dmg == 32767) dmg = 32767;
        return ((long) stack.itemID << 16) | (dmg & 0xFFFF);
    }

    private long itemKeyWildcard(ItemStack stack) {
        int dmg = stack.getItemDamage();
        if (dmg < 0 || dmg == 32767) dmg = 32767;
        else if (!stack.getItem().getHasSubtypes()) dmg = 0;
        return ((long) stack.itemID << 16) | (dmg & 0xFFFF);
    }

    private ItemStack keyToSampleStack(long key) {
        int id = (int) (key >> 16);
        int dmg = (int) (key & 0xFFFF);
        if (dmg == 0xFFFF) dmg = 32767;
        try { return new ItemStack(id, 1, dmg); }
        catch (Throwable t) { return null; }
    }

    private boolean itemMatches(ItemStack stack, ItemStack needed) {
        if (stack == null || needed == null) return false;
        if (stack.itemID != needed.itemID) return false;
        int needDmg = needed.getItemDamage();
        if (needDmg == 32767 || needDmg == -1) return true;
        return stack.getItemDamage() == needDmg;
    }

    private int[] getShapedDimensions(ShapedRecipes recipe) {
        int[] dims = new int[] {-1, -1};
        try {
            for (Field f : ShapedRecipes.class.getDeclaredFields()) {
                f.setAccessible(true);
                if (f.getType() == int.class) {
                    String name = f.getName();
                    if (name.equals("recipeWidth") || name.equals("field_77576_b") || name.equals("b")) dims[0] = f.getInt(recipe);
                    else if (name.equals("recipeHeight") || name.equals("field_77577_c") || name.equals("c")) dims[1] = f.getInt(recipe);
                }
            }
        } catch (Throwable t) {}

        ItemStack[] items = getShapedIngredients(recipe);
        int len = items != null ? items.length : 0;

        if ((dims[0] <= 0 || dims[1] <= 0) && len > 0) {
            if (len == 1) { dims[0] = 1; dims[1] = 1; }
            else if (len == 2) { dims[0] = 1; dims[1] = 2; }
            else if (len == 3) { dims[0] = 3; dims[1] = 1; }
            else if (len == 4) { dims[0] = 2; dims[1] = 2; }
            else if (len == 6) { dims[0] = 3; dims[1] = 2; }
            else if (len == 9) { dims[0] = 3; dims[1] = 3; }
        }
        return dims;
    }

    private int[] getShapedOreDimensions(ShapedOreRecipe recipe) {
        int[] dims = new int[] {-1, -1};
        try {
            for (Field f : ShapedOreRecipe.class.getDeclaredFields()) {
                f.setAccessible(true);
                if (f.getType() == int.class) {
                    String name = f.getName();
                    if (name.equals("width")) dims[0] = f.getInt(recipe);
                    else if (name.equals("height")) dims[1] = f.getInt(recipe);
                }
            }
        } catch (Throwable t) {}

        Object[] items = getShapedOrePatternObjects(recipe);
        int len = items != null ? items.length : 0;

        if ((dims[0] <= 0 || dims[1] <= 0) && len > 0) {
            if (len == 1) { dims[0] = 1; dims[1] = 1; }
            else if (len == 2) { dims[0] = 1; dims[1] = 2; }
            else if (len == 3) { dims[0] = 3; dims[1] = 1; }
            else if (len == 4) { dims[0] = 2; dims[1] = 2; }
            else if (len == 6) { dims[0] = 3; dims[1] = 2; }
            else if (len == 9) { dims[0] = 3; dims[1] = 3; }
        }
        return dims;
    }

    private Object[] getShapedOrePatternObjects(ShapedOreRecipe recipe) {
        try {
            for (Field f : ShapedOreRecipe.class.getDeclaredFields()) {
                if (f.getType() == Object[].class) {
                    f.setAccessible(true);
                    return (Object[]) f.get(recipe);
                }
            }
        } catch (Throwable t) {}
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Object> getShapelessOrePatternObjects(ShapelessOreRecipe recipe) {
        try {
            for (Field f : ShapelessOreRecipe.class.getDeclaredFields()) {
                if (List.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return (List<Object>) f.get(recipe);
                }
            }
        } catch (Throwable t) {}
        return null;
    }

    private ItemStack[] getShapedIngredients(ShapedRecipes recipe) {
        try {
            for (Field f : ShapedRecipes.class.getDeclaredFields()) {
                if (f.getType() == ItemStack[].class) {
                    f.setAccessible(true);
                    return (ItemStack[]) f.get(recipe);
                }
            }
        } catch (Throwable t) {}
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<ItemStack> getShapelessIngredients(ShapelessRecipes recipe) {
        try {
            for (Field f : ShapelessRecipes.class.getDeclaredFields()) {
                if (List.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return (List<ItemStack>) f.get(recipe);
                }
            }
        } catch (Throwable t) {}
        return null;
    }

    // =========================================================================
    // Slot detection  obfuscation-safe, class-name-free.
    //
    // In a 1.4.7 obfuscated jar ALL class simple names are single letters (sq,
    // sr, etc.) so we cannot use string matching like "SlotCrafting". Instead
    // we detect slots structurally:
    //
    //   SlotCrafting   the output slot; it is the ONLY slot whose class differs
    //                   from the plain grid-slot class.  In ContainerPlayer the
    //                   layout is [0]=output [1-4]=2x2 grid [5-8]=armor [9+]=inv.
    //                   In ContainerWorkbench: [0]=output [1-9]=3x3 grid [10+]=inv.
    //
    //   Strategy 1: Find the first slot whose class is unique (appears exactly
    //               once in the first ~15 slots). That is the output. The next
    //               4 or 9 slots of the MAJORITY class are the inputs.
    //
    //   Strategy 2: If that fails, take slots [1..4] or [1..9] and verify they
    //               all share the same class (plain grid slots).
    // =========================================================================
    private List<Slot> findCraftingInputSlots(GuiContainer gui) {
        try {
            List<Slot> allSlots = mc.thePlayer.openContainer.inventorySlots;
            if (allSlots == null || allSlots.isEmpty()) return null;

            int scanLimit = Math.min(allSlots.size(), 15);

            //  Strategy 1: unique-class slot is the output 
            // Count how many times each class appears in the first scanLimit slots
            Map<Class<?>, Integer> classCounts = new LinkedHashMap<Class<?>, Integer>();
            for (int i = 0; i < scanLimit; i++) {
                Class<?> c = allSlots.get(i).getClass();
                Integer n = classCounts.get(c);
                classCounts.put(c, n == null ? 1 : n + 1);
            }

            // The output slot class appears exactly once; the input slot class
            // appears 4 or 9 times (2x2 or 3x3 grid).
            Class<?> outputClass = null;
            Class<?> inputClass  = null;
            for (Map.Entry<Class<?>, Integer> e : classCounts.entrySet()) {
                if (e.getValue() == 1)               outputClass = e.getKey();
                if (e.getValue() == 4 || e.getValue() == 9) inputClass  = e.getKey();
            }

            if (outputClass != null && inputClass != null) {
                // Find the output slot index
                int outputIdx = -1;
                for (int i = 0; i < scanLimit; i++) {
                    if (allSlots.get(i).getClass() == outputClass) { outputIdx = i; break; }
                }
                if (outputIdx >= 0) {
                    List<Slot> inputs = new ArrayList<Slot>();
                    for (int i = outputIdx + 1; i < allSlots.size(); i++) {
                        if (allSlots.get(i).getClass() == inputClass) {
                            inputs.add(allSlots.get(i));
                            if (inputs.size() == 9) break;
                        } else {
                            break;
                        }
                    }
                    if (inputs.size() == 4 || inputs.size() == 9) {
                        System.out.println("[XInputMod] findCraftingInputSlots: " + inputs.size()
                            + " input slots via unique-class strategy"
                            + " (output=" + outputClass.getSimpleName()
                            + " input=" + inputClass.getSimpleName() + ")");
                        return inputs;
                    }
                }
            }

            //  Strategy 2: slots [1..4] or [1..9] all same class 
            for (int gridSize : new int[]{9, 4}) {
                if (allSlots.size() <= gridSize) continue;
                Class<?> cls = allSlots.get(1).getClass();
                boolean allSame = true;
                for (int i = 2; i <= gridSize; i++) {
                    if (allSlots.get(i).getClass() != cls) { allSame = false; break; }
                }
                if (allSame) {
                    List<Slot> inputs = new ArrayList<Slot>();
                    for (int i = 1; i <= gridSize; i++) inputs.add(allSlots.get(i));
                    System.out.println("[XInputMod] findCraftingInputSlots: " + inputs.size()
                        + " input slots via positional fallback (class="
                        + cls.getSimpleName() + ")");
                    return inputs;
                }
            }

            //  Log all slot classes so failures can be diagnosed 
            System.out.println("[XInputMod] findCraftingInputSlots: no grid found. Slot classes:");
            for (int i = 0; i < scanLimit; i++)
                System.out.println("[XInputMod]   [" + i + "] "
                    + allSlots.get(i).getClass().getSimpleName());

        } catch (Throwable t) {
            System.out.println("[XInputMod] findCraftingInputSlots failed: " + t);
        }
        return null;
    }

    private void clearCraftingSlots(List<Slot> craftingSlots) {
        try {
            int windowId = mc.thePlayer.openContainer.windowId;
            for (Slot s : craftingSlots) {
                if (s.getHasStack()) mc.playerController.windowClick(windowId, s.slotNumber, 0, 1, mc.thePlayer);
            }
        } catch (Throwable t) {}
    }

    private void placeItemInSlot(ItemStack needed, Slot craftingSlot) {
        try {
            int windowId = mc.thePlayer.openContainer.windowId;
            List<Slot> allSlots = mc.thePlayer.openContainer.inventorySlots;
            Slot source = null;
            for (Slot s : allSlots) {
                if (!s.getHasStack()) continue;
                if (s.slotNumber == 0) continue;
                if (isCraftingInputSlotStored(s)) continue;
                if (itemMatches(s.getStack(), needed)) {
                    source = s;
                    break;
                }
            }
            if (source == null) return;
            mc.playerController.windowClick(windowId, source.slotNumber, 0, 0, mc.thePlayer);
            mc.playerController.windowClick(windowId, craftingSlot.slotNumber, 1, 0, mc.thePlayer);
            mc.playerController.windowClick(windowId, source.slotNumber, 0, 0, mc.thePlayer);
        } catch (Throwable t) {}
    }

    private boolean isCraftingInputSlotStored(Slot s) {
        if (lastFoundCraftingSlots == null) return false;
        for (Slot cs : lastFoundCraftingSlots) if (cs == s) return true;
        return false;
    }

    private boolean fillGrid(IRecipe recipe) {
        if (mc.thePlayer == null || mc.currentScreen == null) return false;
        if (!(mc.currentScreen instanceof GuiContainer)) return false;
        GuiContainer gui = (GuiContainer) mc.currentScreen;
        List<Slot> craftingSlots = findCraftingInputSlots(gui);
        if (craftingSlots == null || craftingSlots.isEmpty()) return false;
        int gridSize = craftingSlots.size();
        int gridW = (int) Math.sqrt(gridSize);
        ItemStack[] pattern = null;

        try {
            if (recipe instanceof ShapedRecipes) pattern = buildShapedPattern((ShapedRecipes) recipe, gridW);
            else if (recipe instanceof ShapelessRecipes) pattern = buildShapelessPattern((ShapelessRecipes) recipe, gridSize);
            else if (recipe instanceof ShapedOreRecipe) pattern = buildShapedOrePatternForFill((ShapedOreRecipe) recipe, gridW);
            else if (recipe instanceof ShapelessOreRecipe) pattern = buildShapelessOrePatternForFill((ShapelessOreRecipe) recipe, gridSize);
        } catch (Throwable t) { return false; }

        if (pattern == null) return false;
        lastFoundCraftingSlots = craftingSlots;
        clearCraftingSlots(craftingSlots);

        for (int i = 0; i < Math.min(pattern.length, craftingSlots.size()); i++) {
            ItemStack needed = pattern[i];
            if (needed == null) continue;
            Slot slot = craftingSlots.get(i);
            placeItemInSlot(needed, slot);
        }
        return true;
    }

    private ItemStack[] buildShapedPattern(ShapedRecipes recipe, int gridW) {
        ItemStack[] ingredients = getShapedIngredients(recipe);
        int[] dims = getShapedDimensions(recipe);
        int recipeW = dims[0], recipeH = dims[1];

        if (ingredients == null || recipeW <= 0 || recipeH <= 0 || recipeW > gridW || recipeH > gridW) return null;

        ItemStack[] pattern = new ItemStack[gridW * gridW];
        int offsetX = (gridW - recipeW) / 2;
        int offsetY = (gridW - recipeH) / 2;
        for (int row = 0; row < recipeH; row++) {
            for (int col = 0; col < recipeW; col++) {
                int srcIdx = row * recipeW + col;
                int dstIdx = (row + offsetY) * gridW + (col + offsetX);
                if (srcIdx < ingredients.length) pattern[dstIdx] = ingredients[srcIdx] == null ? null : ingredients[srcIdx].copy();
            }
        }
        return pattern;
    }

    private ItemStack[] buildShapelessPattern(ShapelessRecipes recipe, int gridSize) {
        List<ItemStack> ingredients = getShapelessIngredients(recipe);
        if (ingredients == null) return null;
        ItemStack[] pattern = new ItemStack[gridSize];
        for (int i = 0; i < Math.min(ingredients.size(), gridSize); i++)
            pattern[i] = ingredients.get(i) == null ? null : ingredients.get(i).copy();
        return pattern;
    }

    private ItemStack[] buildShapedOrePatternForFill(ShapedOreRecipe recipe, int gridW) {
        try {
            Object[] input = getShapedOrePatternObjects(recipe);
            int[] dims = getShapedOreDimensions(recipe);
            int recipeW = dims[0], recipeH = dims[1];

            if (input == null || recipeW <= 0 || recipeH <= 0 || recipeW > gridW || recipeH > gridW) return null;

            ItemStack[] resolved = new ItemStack[input.length];
            for (int i = 0; i < input.length; i++) {
                ItemStack r = resolveOreIngredientPreferPlayer(input[i]);
                resolved[i] = (r == null) ? null : r.copy();
            }

            ItemStack[] pattern = new ItemStack[gridW * gridW];
            int offsetX = (gridW - recipeW) / 2;
            int offsetY = (gridW - recipeH) / 2;
            for (int row = 0; row < recipeH; row++) {
                for (int col = 0; col < recipeW; col++) {
                    int src = row * recipeW + col;
                    int dst = (row + offsetY) * gridW + (col + offsetX);
                    if (src < resolved.length) pattern[dst] = resolved[src];
                }
            }
            return pattern;
        } catch (Throwable t) { return null; }
    }

    private ItemStack[] buildShapelessOrePatternForFill(ShapelessOreRecipe recipe, int gridSize) {
        try {
            List<Object> ingredients = getShapelessOrePatternObjects(recipe);
            if (ingredients == null) return null;
            ItemStack[] pattern = new ItemStack[gridSize];
            for (int i = 0; i < Math.min(ingredients.size(), gridSize); i++) {
                ItemStack r = resolveOreIngredientPreferPlayer(ingredients.get(i));
                pattern[i] = (r == null) ? null : r.copy();
            }
            return pattern;
        } catch (Throwable t) { return null; }
    }

    private void renderItemStack(ItemStack stack, int x, int y) {
        try {
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            RenderHelper.enableGUIStandardItemLighting();
            itemRenderer.renderItemAndEffectIntoGUI(mc.fontRenderer, mc.renderEngine, stack, x, y);
            RenderHelper.disableStandardItemLighting();
        } catch (Throwable ignored) {}
    }

    private void drawRect(int x1, int y1, int x2, int y2, int color) {
        float a = ((color >> 24) & 0xFF) / 255f;
        float r = ((color >> 16) & 0xFF) / 255f;
        float g = ((color >> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(r, g, b, a);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2i(x1, y1);
        GL11.glVertex2i(x1, y2);
        GL11.glVertex2i(x2, y2);
        GL11.glVertex2i(x2, y1);
        GL11.glEnd();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }
}