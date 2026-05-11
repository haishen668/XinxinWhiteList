package ltd.dreamcraft.xinxinwhitelist.listeners;

import com.xinxin.BotApi.BotBind;
import ltd.dreamcraft.xinxinwhitelist.XinxinWhiteList;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class onLogin implements Listener {
    public static AtomicInteger attacks = new AtomicInteger(0);

    public static volatile Long last = System.currentTimeMillis();

    public static Set<UUID> admins = ConcurrentHashMap.newKeySet();
//  private static List<GroupMember> GroupMembersList = new ArrayList<>();

//  public onLogin() {
//    if (XinxinWhiteList.getInstance().getConfig().getBoolean("kick_unbind")) {
//      for (String groupId : XinxinWhiteList.getInstance().getConfig().getStringList("groups")) {
//        Bukkit.getScheduler().runTaskTimerAsynchronously(XinxinWhiteList.getInstance(), () -> {
//          GroupMembersList = BotActionLocal.getGroupMemberList(Long.parseLong(groupId));
//        }, 0L, 24000L); // 修改为20分钟一次
//      }
//    }
//  }


    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (p.hasPermission("admin"))
            admins.add(e.getPlayer().getUniqueId());
        if (!XinxinWhiteList.getInstance().getConfig().getBoolean("force_bind"))
            return;
        Bukkit.getScheduler().runTaskAsynchronously(XinxinWhiteList.getInstance(), () -> {
            String qqx = XinxinWhiteList.getPlayerData().getPlayerName(p.getName().toLowerCase());
            if (qqx != null)
                //1.0.9bug修复
                if (!BotBind.setBind(qqx, p.getName())) {
                    BotBind.addBind(qqx, p.getName());
                }
        });
    }

    @EventHandler
    public void onLog(AsyncPlayerPreLoginEvent e) {
        last = System.currentTimeMillis();
        int currentAttacks = attacks.incrementAndGet();
        if (currentAttacks > 20)
            for (UUID uuid : admins) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null)
                    p.sendMessage("§b§l已拦截攻击[§7§l" + e.getAddress().getHostAddress() + "§b§l|§7§l" + e.getName() + "§b§l]数量: §c§l" + currentAttacks);
            }
    }

//  @EventHandler
//  public void onLoginFilterPlayers(AsyncPlayerPreLoginEvent e) {
//
//    if (XinxinWhiteList.getInstance().getConfig().getBoolean("kick_unbind")) {
//      //存在一个多群聊就会出现bug的问题，懒得改
//      for (String groupId : XinxinWhiteList.getInstance().getConfig().getStringList("groups")) {
//          GroupMembersList = BotActionLocal.getGroupMemberList(Long.parseLong(groupId));
//      }
//      CustomConfig playerData = XinxinWhiteList.getPlayerName();
//      FileConfiguration config = playerData.getConfig();
//      String string = config.getString(e.getName().toLowerCase());
//      for (GroupMember member : GroupMembersList) {
//        if (member.getUserId() == Long.parseLong(string))
//          return;
//      }
//      //进行账号踢出
//      XinxinWhiteList.removePlayerByID(e.getName());
//      e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, "§b§l你不在交流群中已删除你的白名单");
//    }
//
//  }
}

