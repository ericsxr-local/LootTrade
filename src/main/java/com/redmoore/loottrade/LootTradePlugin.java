package com.redmoore.loottrade;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Arrays;
import java.util.Objects;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.lang.reflect.Field;

/**
 * LootTrade - Purpur/Paper 1.20.1 + Java 17 + Vault.
 *
 * 识别规则：
 * 1) 物品必须有自定义显示名称，并且名称中必须出现至少一个真正的颜色代码；
 * 2) Lore 必须有一行，同时包含颜色代码以及“售价：数字”。
 *
 * Material、Lore 分类、LootID 等均不参与出售判定。
 */
public final class LootTradePlugin extends JavaPlugin implements CommandExecutor, TabCompleter {

    private static final Pattern COLOR_PATTERN = Pattern.compile("(?i)§(?:[0-9a-f]|x(?:§[0-9a-f]){6})");
    private static final Pattern PRICE_PATTERN = Pattern.compile("售价：\\s*(\\d+(?:\\.\\d+)?)");
    private static final DecimalFormat MONEY_FORMAT = new DecimalFormat("0.##");

    private Economy economy;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        if (!setupEconomy()) {
            getLogger().severe("未找到 Vault Economy 提供者。请安装 Vault + 一个经济插件后重启服务器。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        PluginCommand command = getCommand("lt");
        if (command == null) {
            getLogger().severe("plugin.yml 中找不到 lt 命令，插件无法正常工作。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        command.setExecutor(this);
        command.setTabCompleter(this);

        getLogger().info("LootTrade 已启用。识别规则：彩色名称 + 彩色“售价：数字”Lore。经济提供者：" + economy.getName());
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        economy = rsp.getProvider();
        return economy != null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("lt")) {
            return false;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("buy")) {
            // 购买命令必须由命令方块执行，玩家不能直接执行。
            // 用法：/lt buy <价格>
            if (!(sender instanceof BlockCommandSender commandBlock)) {
                send(sender, "buy-command-block-only");
                return true;
            }
            if (args.length != 2) {
                send(sender, "usage");
                return true;
            }

            Player buyer = findNearestEligibleBuyer(commandBlock);
            if (buyer == null) {
                send(sender, "buy-no-eligible-player");
                return true;
            }

            handleBuy(buyer, args[1]);
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("sellall")) {
            if (!(sender instanceof Player player)) {
                send(sender, "player-only");
                return true;
            }
            if (!player.hasPermission("loottrade.sellall")) {
                send(sender, "no-permission");
                return true;
            }

            if (isInActiveStprMatch(player)) {
                send(player, "blocked-in-match");
                return true;
            }

            sellForPlayer(player, player, false);
            return true;
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("sell") && args[2].equalsIgnoreCase("all")) {
            if (!sender.hasPermission("loottrade.admin")) {
                send(sender, "no-permission");
                return true;
            }

            List<Player> targets = resolveTargets(sender, args[1]);
            if (targets.isEmpty()) {
                send(sender, "player-not-found", Map.of("player", args[1]));
                return true;
            }

            int successTargets = 0;
            int blockedTargets = 0;

            for (Player target : targets) {
                if (isInActiveStprMatch(target)) {
                    blockedTargets++;
                    continue;
                }
                sellForPlayer(sender, target, true);
                successTargets++;
            }

            if (blockedTargets > 0) {
                send(sender, "blocked-target-count", Map.of("count", Integer.toString(blockedTargets)));
            }
            if (successTargets == 0 && blockedTargets > 0) {
                send(sender, "all-targets-in-match");
            }
            return true;
        }

        send(sender, "usage");
        return true;
    }

    /**
     * 支持普通玩家名，以及 @p / @a / @r。
     *
     * @p：命令执行位置所在世界中最近的玩家（玩家执行时排除自己）
     * @a：所有在线玩家
     * @r：命令执行位置所在世界中随机一名玩家
     */
    private List<Player> resolveTargets(CommandSender sender, String selectorOrName) {
        String value = selectorOrName == null ? "" : selectorOrName.trim();

        if (value.equalsIgnoreCase("@a")) {
            return new ArrayList<>(Bukkit.getOnlinePlayers());
        }

        if (value.equalsIgnoreCase("@p")) {
            Player nearest = findNearestPlayer(sender);
            return nearest == null ? Collections.emptyList() : List.of(nearest);
        }

        if (value.equalsIgnoreCase("@r")) {
            List<Player> candidates = playersInSenderWorld(sender);
            if (candidates.isEmpty()) {
                return Collections.emptyList();
            }
            Collections.shuffle(candidates);
            return List.of(candidates.get(0));
        }

        Player direct = Bukkit.getPlayerExact(value);
        return direct == null ? Collections.emptyList() : List.of(direct);
    }

    private List<Player> playersInSenderWorld(CommandSender sender) {
        org.bukkit.World world = getSenderWorld(sender);
        if (world == null) {
            return Collections.emptyList();
        }

        return new ArrayList<>(world.getPlayers());
    }

    private org.bukkit.World getSenderWorld(CommandSender sender) {
        if (sender instanceof Player player) {
            return player.getWorld();
        }
        if (sender instanceof BlockCommandSender commandBlock) {
            return commandBlock.getBlock().getWorld();
        }
        return null;
    }

    private Player findNearestPlayer(CommandSender sender) {
        org.bukkit.World world = getSenderWorld(sender);
        if (world == null) {
            return null;
        }

        org.bukkit.Location source;
        boolean excludeSelf = sender instanceof Player;

        if (sender instanceof Player player) {
            source = player.getLocation();
        } else if (sender instanceof BlockCommandSender commandBlock) {
            source = commandBlock.getBlock().getLocation().add(0.5, 0.5, 0.5);
        } else {
            return null;
        }

        Player nearest = null;
        double nearestSquared = Double.MAX_VALUE;

        for (Player candidate : world.getPlayers()) {
            if (excludeSelf && sender instanceof Player player && candidate.equals(player)) {
                continue;
            }
            double distanceSquared = source.distanceSquared(candidate.getLocation());
            if (distanceSquared < nearestSquared) {
                nearestSquared = distanceSquared;
                nearest = candidate;
            }
        }
        return nearest;
    }

    /**
     * 从命令方块所在位置寻找“最近的、正站在下界合金块上的玩家”。
     * 购买商品仍然严格读取该玩家脚下两格的木桶。
     */
    private Player findNearestEligibleBuyer(BlockCommandSender commandBlock) {
        Block block = commandBlock.getBlock();
        double radius = getConfig().getDouble("buy.search-radius", 3.0);
        if (!Double.isFinite(radius) || radius < 0.0) {
            radius = 3.0;
        }

        Player nearest = null;
        double nearestSquared = radius * radius;

        for (Player candidate : block.getWorld().getPlayers()) {
            // 玩家脚下必须正好是下界合金块。
            Block standingOn = candidate.getLocation().getBlock().getRelative(BlockFace.DOWN);
            if (standingOn.getType() != Material.NETHERITE_BLOCK) {
                continue;
            }

            double distanceSquared = block.getLocation().distanceSquared(candidate.getLocation());
            if (distanceSquared <= nearestSquared) {
                if (nearest == null || distanceSquared < block.getLocation().distanceSquared(nearest.getLocation())) {
                    nearest = candidate;
                    nearestSquared = distanceSquared;
                }
            }
        }

        return nearest;
    }

    /**
     * 购买：由命令方块执行 /lt buy <价格>。
     * 命令方块会寻找最近的、站在下界合金块上的玩家。
     * 该玩家脚下两格必须是木桶；木桶中的物品作为商品模板，购买成功后不会清空木桶。
     */
    private void handleBuy(Player player, String priceText) {
        final double price;
        try {
            price = Double.parseDouble(priceText);
        } catch (NumberFormatException ex) {
            send(player, "invalid-price", Map.of("price", priceText));
            return;
        }

        if (!Double.isFinite(price) || price <= 0.0) {
            send(player, "invalid-price", Map.of("price", priceText));
            return;
        }

        Block barrelBlock = player.getLocation().getBlock().getRelative(BlockFace.DOWN, 2);
        if (barrelBlock.getType() != Material.BARREL) {
            send(player, "buy-no-barrel");
            return;
        }

        Barrel barrel = (Barrel) barrelBlock.getState();
        ItemStack[] goods = Arrays.stream(barrel.getInventory().getContents())
                .filter(Objects::nonNull)
                .filter(item -> !item.getType().isAir())
                .map(ItemStack::clone)
                .toArray(ItemStack[]::new);

        if (goods.length == 0) {
            send(player, "buy-empty-barrel");
            return;
        }

        if (!economy.has(player, price)) {
            send(player, "buy-not-enough", Map.of(
                    "price", formatMoney(price),
                    "balance", formatMoney(economy.getBalance(player))
            ));
            return;
        }

        // 先在临时库存中模拟放入，避免空间不足导致扣钱或只领取部分商品。
        org.bukkit.inventory.Inventory simulated = Bukkit.createInventory(null, 36);
        simulated.setContents(Arrays.copyOf(player.getInventory().getStorageContents(), 36));
        for (ItemStack good : goods) {
            Map<Integer, ItemStack> left = simulated.addItem(good.clone());
            if (!left.isEmpty()) {
                send(player, "buy-inventory-full");
                return;
            }
        }

        EconomyResponse response = economy.withdrawPlayer(player, price);
        if (!response.transactionSuccess()) {
            getLogger().warning("Vault 扣款失败，玩家=" + player.getName() + "，金额=" + price + "，原因=" + response.errorMessage);
            send(player, "buy-transaction-failed");
            return;
        }

        // 扣款成功后正式发放商品。木桶内容保持不变，可重复购买。
        for (ItemStack good : goods) {
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(good.clone());
            if (!leftovers.isEmpty()) {
                // 理论上不会发生，因为前面已经完整模拟过空间；若发生则记录日志并把无法放下的商品掉落。
                for (ItemStack leftover : leftovers.values()) {
                    if (leftover != null && !leftover.getType().isAir()) {
                        player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                    }
                }
                getLogger().severe("购买发放时出现意外背包空间变化，玩家=" + player.getName() + "，已将剩余商品掉落在玩家脚边。");
            }
        }

        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 1.0f, 1.5f);
        send(player, "buy-success");
        send(player, "buy-paid", Map.of("price", formatMoney(price)));
    }


    /**
     * 直接检测目标玩家是否正在 STPR 的 ACTIVE 对局中。
     *
     * 不直接依赖 STPR 的编译包，而是读取 STPRPlugin 当前暴露/维护的
     * playerMatches 数据，避免 LootTrade 强绑定 STPR jar。
     *
     * 若 STPR 未安装或版本不兼容，则视为“不在局内”，不会影响普通服务器使用。
     */
    private boolean isInActiveStprMatch(Player player) {
        if (!getConfig().getBoolean("sell.block-while-in-stpr-match", true)) {
            return false;
        }

        org.bukkit.plugin.Plugin stpr = Bukkit.getPluginManager().getPlugin("STPR");
        if (stpr == null || !stpr.isEnabled()) {
            return false;
        }

        try {
            Field field = stpr.getClass().getDeclaredField("playerMatches");
            field.setAccessible(true);

            Object value = field.get(stpr);
            if (!(value instanceof Map<?, ?> playerMatches)) {
                return false;
            }

            Object match = playerMatches.get(player.getUniqueId());
            if (match == null) {
                return false;
            }

            Field stateField = match.getClass().getDeclaredField("state");
            stateField.setAccessible(true);
            Object state = stateField.get(match);

            return state != null && "ACTIVE".equalsIgnoreCase(state.toString());
        } catch (ReflectiveOperationException | SecurityException ex) {
            getLogger().warning("无法读取 STPR 当前对局状态，已按“不在局内”处理。原因：" + ex.getClass().getSimpleName());
            return false;
        }
    }

    private void sellForPlayer(CommandSender executor, Player target, boolean delegated) {
        if (economy == null) {
            send(executor, "economy-missing");
            return;
        }

        SellResult result = inspect(target);
        if (result.itemCount() == 0) {
            send(executor, delegated ? "no-items-target" : "no-items-self");
            return;
        }

        EconomyResponse response = economy.depositPlayer(target, result.totalValue());
        if (!response.transactionSuccess()) {
            getLogger().warning("Vault 入账失败，玩家=" + target.getName() + "，金额=" + result.totalValue() + "，原因=" + response.errorMessage);
            send(executor, "transaction-failed");
            return;
        }

        removeSoldItems(target.getInventory(), result.slots(), getConfig().getBoolean("include-offhand", true));

        if (delegated) {
            send(executor, "sold-target", Map.of(
                    "player", target.getName(),
                    "items", Integer.toString(result.itemCount()),
                    "money", formatMoney(result.totalValue())
            ));
            if (executor instanceof Player player && !player.equals(target)) {
                target.sendMessage(colorize(getMessage("prefix") + "&a你的背包战利品已被管理操作出售，获得 &6" + formatMoney(result.totalValue()) + "&a。"));
            }
        } else {
            send(executor, "sold-self", Map.of(
                    "items", Integer.toString(result.itemCount()),
                    "money", formatMoney(result.totalValue())
            ));
        }
    }

    private SellResult inspect(Player player) {
        PlayerInventory inventory = player.getInventory();
        List<Integer> slots = new ArrayList<>();
        double total = 0.0;
        int itemCount = 0;

        ItemStack[] storage = inventory.getStorageContents();
        for (int slot = 0; slot < storage.length; slot++) {
            ItemStack stack = storage[slot];
            PriceResult price = getSellPrice(stack);
            if (!price.valid()) {
                continue;
            }

            double stackValue = price.value() * stack.getAmount();
            if (!Double.isFinite(stackValue)) {
                continue;
            }
            total += stackValue;
            itemCount += stack.getAmount();
            slots.add(slot);
        }

        if (getConfig().getBoolean("include-offhand", true)) {
            ItemStack offhand = inventory.getItemInOffHand();
            PriceResult price = getSellPrice(offhand);
            if (price.valid() && Double.isFinite(price.value() * offhand.getAmount())) {
                total += price.value() * offhand.getAmount();
                itemCount += offhand.getAmount();
                slots.add(40); // Bukkit PlayerInventory offhand slot.
            }
        }

        return new SellResult(slots, itemCount, total);
    }

    private void removeSoldItems(PlayerInventory inventory, List<Integer> slots, boolean includeOffhand) {
        for (Integer slot : slots) {
            if (slot >= 0 && slot < 36) {
                inventory.setItem(slot, null);
            } else if (slot == 40 && includeOffhand) {
                inventory.setItemInOffHand(null);
            }
        }
    }

    /**
     * 严格按用户约定识别：
     * - 自定义名称必须带“颜色”代码（不是仅仅粗体等格式）；
     * - 售价所在 Lore 行必须也带颜色代码；
     * - 售价格式固定为：售价：数字。
     */
    private PriceResult getSellPrice(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return PriceResult.invalid();
        }

        ItemMeta meta = stack.getItemMeta();
        if (meta == null || !meta.hasDisplayName() || !hasColor(meta.getDisplayName())) {
            return PriceResult.invalid();
        }

        if (!meta.hasLore() || meta.getLore() == null) {
            return PriceResult.invalid();
        }

        for (String line : meta.getLore()) {
            if (line == null || !hasColor(line)) {
                continue;
            }
            Matcher matcher = PRICE_PATTERN.matcher(ChatColor.stripColor(line));
            if (!matcher.find()) {
                continue;
            }
            try {
                double price = Double.parseDouble(matcher.group(1));
                if (Double.isFinite(price) && price >= 0.0) {
                    return new PriceResult(true, price);
                }
            } catch (NumberFormatException ignored) {
                // 继续检查下一行。
            }
        }

        return PriceResult.invalid();
    }

    private boolean hasColor(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return COLOR_PATTERN.matcher(text).find();
    }

    private void send(CommandSender sender, String key) {
        sender.sendMessage(colorize(getMessage("prefix") + getMessage(key)));
    }

    private void send(CommandSender sender, String key, Map<String, String> placeholders) {
        String message = getMessage("prefix") + getMessage(key);
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            message = message.replace("%" + entry.getKey() + "%", entry.getValue());
        }
        sender.sendMessage(colorize(message));
    }

    private String getMessage(String key) {
        return getConfig().getString("messages." + key, key);
    }

    private String colorize(String text) {
        return ChatColor.translateAlternateColorCodes('&', Objects.requireNonNullElse(text, ""));
    }

    private String formatMoney(double value) {
        synchronized (MONEY_FORMAT) {
            return MONEY_FORMAT.format(value);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("lt")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            List<String> values = new ArrayList<>();
            values.add("sellall");
            values.add("buy");
            if (sender.hasPermission("loottrade.admin")) {
                values.add("sell");
            }
            return partial(values, args[0]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("buy")) {
            return Collections.emptyList();
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("sell") && sender.hasPermission("loottrade.admin")) {
            List<String> values = new ArrayList<>();
            values.add("@p");
            for (Player player : Bukkit.getOnlinePlayers()) {
                values.add(player.getName());
            }
            return partial(values, args[1]);
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("sell") && sender.hasPermission("loottrade.admin")) {
            return partial(List.of("all"), args[2]);
        }

        return Collections.emptyList();
    }

    private List<String> partial(List<String> values, String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        return values.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower))
                .sorted()
                .toList();
    }

    private record PriceResult(boolean valid, double value) {
        static PriceResult invalid() {
            return new PriceResult(false, 0.0);
        }
    }

    private record SellResult(List<Integer> slots, int itemCount, double totalValue) {
    }
}
