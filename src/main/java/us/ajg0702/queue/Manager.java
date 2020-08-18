package us.ajg0702.queue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.ServerPing;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import us.ajg0702.utils.bungee.BungeeMessages;
import us.ajg0702.utils.bungee.BungeeUtils;

public class Manager {
	
	static Manager INSTANCE = null;
	public static Manager getInstance(Main pl) {
		if(INSTANCE == null) {
			INSTANCE = new Manager(pl);
		}
		return INSTANCE;
	}
	public static Manager getInstance() {
		return INSTANCE;
	}
	
	BungeeMessages msgs;
	
	
	Main pl;
	private Manager(Main pl) {
		this.pl = pl;
		msgs = BungeeMessages.getInstance();
		reloadIntervals();
		if(!pl.config.getBoolean("wait-to-load-servers")) {
			reloadServers();
		} else {
			pl.getProxy().getScheduler().schedule(pl, new Runnable() {
				public void run() {
					reloadServers();
				}
			}, pl.config.getInt("wait-to-load-servers-delay"), TimeUnit.MILLISECONDS);
		}
	}
	
	/*
	 * Returns all servers
	 */
	public List<QueueServer> getServers() {
		return servers;
	}
	
	/**
	 * Returns the name of all servers and groups
	 * @return The names of all servers and groups
	 */
	public List<String> getServerNames() {
		List<String> names = new ArrayList<>();
		for(QueueServer s : servers) {
			names.add(s.getName());
		}
		return names;
	}
	
	
	
	int sendId = -1;
	int updateId = -1;
	int messagerId = -1;
	int actionbarId = -1;
	int srvRefId = -1;
	/**
	 * Clears all intervals and re-makes them
	 */
	public void reloadIntervals() {
		if(sendId != -1) {
			pl.getProxy().getScheduler().cancel(sendId);
			sendId = -1;
		}
		if(updateId != -1) {
			pl.getProxy().getScheduler().cancel(updateId);
			updateId = -1;
		}
		if(messagerId != -1) {
			pl.getProxy().getScheduler().cancel(messagerId);
			messagerId = -1;
		}
		if(actionbarId != -1) {
			pl.getProxy().getScheduler().cancel(actionbarId);
			actionbarId = -1;
		}
		if(srvRefId != -1) {
			pl.getProxy().getScheduler().cancel(srvRefId);
			srvRefId = -1;
		}
		
		sendId = pl.getProxy().getScheduler().schedule(pl, new Runnable() {
			public void run() {
				sendPlayers();
			}
		}, 2, Math.round(pl.timeBetweenPlayers*1000), TimeUnit.MILLISECONDS).getId();
		
		updateId = pl.getProxy().getScheduler().schedule(pl, new Runnable() {
			public void run() {
				updateServers();
			}
		}, 0, Math.max(Math.round(pl.timeBetweenPlayers), 2), TimeUnit.SECONDS).getId();
		//pl.getLogger().info("Time: "+pl.timeBetweenPlayers);
		
		messagerId = pl.getProxy().getScheduler().schedule(pl, new Runnable() {
			public void run() {
				sendMessages();
			}
		}, 0, pl.getConfig().getInt("message-time"), TimeUnit.SECONDS).getId();
		actionbarId = pl.getProxy().getScheduler().schedule(pl, new Runnable() {
			public void run() {
				sendActionBars();
			}
		}, 0, 2, TimeUnit.SECONDS).getId();
		
		if(pl.config.getInt("reload-servers-interval") > 0) {
			srvRefId = pl.getProxy().getScheduler().schedule(pl, new Runnable() {
				public void run() {
					updateServers();
				}
			}, pl.config.getInt("reload-servers-interval"),  pl.config.getInt("reload-servers-interval"), TimeUnit.SECONDS).getId();
		}
	}
	
	/**
	 * Get the name of the server the player is queued for.
	 * If multiple servers are queued for, it will use the multi-server-queue-pick option in the config
	 * @param p The player
	 * @return The name of the server, the placeholder none message if not queued
	 */
	public String getQueuedName(ProxiedPlayer p) {
		List<QueueServer> queued = findPlayerInQueue(p);
		if(queued.size() <= 0) {
			return msgs.get("placeholders.queued.none");
		}
		QueueServer selected = queued.get(0);
		
		if(pl.config.getString("multi-server-queue-pick").equalsIgnoreCase("last")) {
			selected = queued.get(queued.size()-1);
		}
		
		return selected.getName();
	}
	
	/**
	 * Get a single server the player is queued for. Depends on the multi-server-queue-pick option in the config
	 * @param p The player
	 * @return The server that was chosen that the player is queued for.
	 */
	public QueueServer getSingleServer(ProxiedPlayer p) {
		List<QueueServer> queued = findPlayerInQueue(p);
		if(queued.size() <= 0) {
			return null;
		}
		QueueServer selected = queued.get(0);
		
		if(pl.config.getString("multi-server-queue-pick").equalsIgnoreCase("last")) {
			selected = queued.get(queued.size()-1);
		}
		return selected;
	}
	
	
	
	
	List<QueueServer> servers = new ArrayList<>();
	/**
	 * Checks servers that are in bungeecord and adds any it doesnt
	 * know about.
	 * 
	 * Also creates/edits server groups
	 */
	public void reloadServers() {
		Map<String, ServerInfo> svs = ProxyServer.getInstance().getServers();
		for(String name : svs.keySet()) {
			if(findServer(name) != null) continue;
			ServerInfo info = svs.get(name);
			servers.add(new QueueServer(name, info));
		}
		
		List<String> groupsraw = pl.config.getStringList("server-groups");
		for(String groupraw : groupsraw) {
			if(groupraw.isEmpty()) {
				pl.getLogger().warning("Empty group string! If you dont want server groups, set server-groups like this: server-groups: []");
				continue;
			}
			
			String groupname = groupraw.split(":")[0];
			String[] serversraw = groupraw.split(":")[1].split(",");
			
			if(getServer(groupname) != null) {
				pl.getLogger().warning("The name of a group ('"+groupname+"') cannot be the same as the name of a server!");
				continue;
			}
			
			List<ServerInfo> servers = new ArrayList<>();
			
			for(String serverraw : serversraw) {
				ServerInfo si = svs.get(serverraw);
				if(si == null) {
					pl.getLogger().warning("Could not find server named '"+serverraw+"' in servergroup '"+groupname+"'!");
					continue;
				}
				servers.add(si);
			}
			
			if(servers.size() == 0) {
				pl.getLogger().warning("Server group '"+groupname+"' has no servers! Ignoring it.");
				continue;
			}
			
			this.servers.add(new QueueServer(groupname, servers));
		}
	}
	
	/**
	 * Sends actionbar updates to all players in all queues with their
	 * position in the queue and time remaining
	 */
	public void sendActionBars() {
		if(!pl.getConfig().getBoolean("send-actionbar")) return;
		
		for(ProxiedPlayer p : ProxyServer.getInstance().getPlayers()) {
			QueueServer s = this.getSingleServer(p);
			
			if(s == null) continue;
			List<ProxiedPlayer> plys = s.getQueue();
			int pos = plys.indexOf(p)+1;
			if(pos == 0) {
				plys.remove(p);
				continue;
			}
			
			int len = plys.size();
			if(!s.isJoinable(p)) {
				
				String status = "unknown";
				
				
				if(!s.canAccess(p)) {
					status = msgs.get("status.offline.restricted");
				}
				
				if(s.isFull()) {
					status = msgs.get("status.offline.full");
				}
				
				if(s.isPaused()) {
					status = msgs.get("status.offline.paused");
				}
				
				if(!s.isOnline()) {
					status = msgs.get("status.offline.restarting");
				}
				
				if(s.getOfflineTime() > pl.config.getInt("offline-time")) {
					status = msgs.get("status.offline.offline");
				}
				
				
				BungeeUtils.sendCustomData(p, "actionbar", msgs.get("spigot.actionbar.offline")
						.replaceAll("\\{POS\\}", pos+"")
						.replaceAll("\\{LEN\\}", len+"")
						.replaceAll("\\{SERVER\\}", pl.aliases.getAlias(s.getName()))
						.replaceAll("\\{STATUS\\}", status)+";time="+pl.timeBetweenPlayers);
			} else {
				int time = (int) Math.round(pos*pl.timeBetweenPlayers);
				int min = (int) Math.floor((time) / (60));
				int sec = (int) Math.floor((time % (60)));
				String timeStr;
	        	if(min <= 0) {
	        		timeStr = msgs.get("format.time.secs")
	        				.replaceAll("\\{m\\}", "0")
	        				.replaceAll("\\{s\\}", sec+"");
	        	} else {
	        		timeStr = msgs.get("format.time.mins")
	        				.replaceAll("\\{m\\}", min+"")
	        				.replaceAll("\\{s\\}", sec+"");
	        	}
				BungeeUtils.sendCustomData(p, "actionbar", msgs.get("spigot.actionbar.online")
						.replaceAll("\\{POS\\}", pos+"")
						.replaceAll("\\{LEN\\}", len+"")
						.replaceAll("\\{SERVER\\}", pl.aliases.getAlias(s.getName()))
						.replaceAll("\\{TIME\\}", timeStr)+";time="+pl.timeBetweenPlayers);
			}
		}
		
		/*for(Server s : servers) {
			int ot = s.getOfflineTime();
			List<ProxiedPlayer> plys = s.getQueue();
			Iterator<ProxiedPlayer> it = plys.iterator();
			while(it.hasNext()) {
				ProxiedPlayer ply = it.next();
				int pos = plys.indexOf(ply)+1;
				if(pos == 0) {
					plys.remove(ply);
					continue;
				}
				
				int len = plys.size();
				if(!s.isOnline() || s.isFull() || !s.canAccess(ply)) {
					
					String status = msgs.get("status.offline.restarting");
					
					if(ot > pl.config.getInt("offline-time")) {
						status = msgs.get("status.offline.offline");
					}
					
					if(!s.canAccess(ply)) {
						status = msgs.get("status.offline.restricted");
					}
					
					
					BungeeUtils.sendCustomData(ply, "actionbar", msgs.get("spigot.actionbar.offline")
							.replaceAll("\\{POS\\}", pos+"")
							.replaceAll("\\{LEN\\}", len+"")
							.replaceAll("\\{SERVER\\}", s.getName())
							.replaceAll("\\{STATUS\\}", status)+";time="+pl.timeBetweenPlayers);
				} else {
					int time = pos*pl.timeBetweenPlayers;
					int min = (int) Math.floor((time) / (60));
					int sec = (int) Math.floor((time % (60)));
					String timeStr;
		        	if(min <= 0) {
		        		timeStr = msgs.get("format.time.secs")
		        				.replaceAll("\\{m\\}", "0")
		        				.replaceAll("\\{s\\}", sec+"");
		        	} else {
		        		timeStr = msgs.get("format.time.mins")
		        				.replaceAll("\\{m\\}", min+"")
		        				.replaceAll("\\{s\\}", sec+"");
		        	}
					BungeeUtils.sendCustomData(ply, "actionbar", msgs.get("spigot.actionbar.online")
							.replaceAll("\\{POS\\}", pos+"")
							.replaceAll("\\{LEN\\}", len+"")
							.replaceAll("\\{SERVER\\}", s.getName())
							.replaceAll("\\{TIME\\}", timeStr)+";time="+pl.timeBetweenPlayers);
				}
			}
		}*/
	}
	
	/**
	 * Sends the message to the player updating them on their position in the queue
	 * along with their time remaining
	 */
	public void sendMessages() {
		for(QueueServer s : servers) {
			int ot = s.getOfflineTime();
			List<ProxiedPlayer> plys = s.getQueue();
			for(ProxiedPlayer ply : plys) {
				int pos = plys.indexOf(ply)+1;
				if(pos == 0) {
					plys.remove(ply);
					continue;
				}
				int len = plys.size();
				if(!s.isJoinable(ply)) {
					
					String status = msgs.get("status.offline.restarting");
					
					if(ot > pl.config.getInt("offline-time")) {
						status = msgs.get("status.offline.offline");
					}
					
					if(s.isFull() && s.isOnline()) {
						status = msgs.get("status.offline.full");
					}
					
					if(!s.canAccess(ply)) {
						status = msgs.get("status.offline.restricted");
					}
					
					if(s.isPaused()) {
						status = msgs.get("status.offline.paused");
					}
					
					ply.sendMessage(Main.formatMessage(
							msgs.get("status.offline.base")
							.replaceAll("\\{STATUS\\}", status)
							.replaceAll("\\{POS\\}", pos+"")
							.replaceAll("\\{LEN\\}", len+"")
							.replaceAll("\\{SERVER\\}", pl.aliases.getAlias(s.getName()))
						));
				} else {
					int time = (int) Math.round(pos*pl.timeBetweenPlayers);
					int min = (int) Math.floor((time) / (60));
					int sec = (int) Math.floor((time % (60)));
					String timeStr;
		        	if(min <= 0) {
		        		timeStr = msgs.get("format.time.secs")
		        				.replaceAll("\\{m\\}", "0")
		        				.replaceAll("\\{s\\}", sec+"");
		        	} else {
		        		timeStr = msgs.get("format.time.mins")
		        				.replaceAll("\\{m\\}", min+"")
		        				.replaceAll("\\{s\\}", sec+"");
		        	}
					ply.sendMessage(Main.formatMessage(
							msgs.get("status.online.base")
							.replaceAll("\\{POS\\}", pos+"")
							.replaceAll("\\{LEN\\}", len+"")
							.replaceAll("\\{TIME\\}", timeStr)
							.replaceAll("\\{SERVER\\}", pl.aliases.getAlias(s.getName()))
							));
				}
				
			}
		}
	}
	
	/**
	 * Find a server by name
	 * @param name Name of the server
	 * @return The server if it exists (otherwise null)
	 */
	public QueueServer findServer(String name) {
		for(QueueServer server : servers) {
			if(server.getName().equals(name)) {
				return server;
			}
		}
		return null;
	}
	
	/**
	 * Updates info about servers.
	 */
	public void updateServers() {
		Iterator<QueueServer> it = servers.iterator();
		while(it.hasNext()) {
			it.next().update();
		}
	}
	
	/**
	 * Attempts to send the first player in all queues
	 */
	public void sendPlayers() {
		sendPlayers(null);
	}
	/**
	 * Attempts to send the first player in this queue
	 * @param server The server to send the first player in the queue. null for all servers.
	 */
	public void sendPlayers(String server) {
		for(QueueServer s : servers) {
			String name = s.getName();
			if(server != null && !server.equals(name)) continue;
			if(!s.isOnline()) continue;
			if(s.isPaused()) continue;
			if(s.getQueue().size() <= 0) continue;
			
			if(pl.config.getBoolean("send-all-when-back-online") && s.justWentOnline() && s.isOnline()) {
				
				
				for(ProxiedPlayer p : s.getQueue()) {
					
					if(s.isFull() && !p.hasPermission("ajqueue.joinfull")) continue;
					
					HashMap<ServerInfo, ServerPing> serverInfos = s.getLastPings();
					ServerInfo selected = null;
					int selectednum = 0;
					for(ServerInfo si : serverInfos.keySet()) {
						ServerPing sp = serverInfos.get(si);
						int online = sp.getPlayers().getOnline();
						if(selected == null) {
							selected = si;
							selectednum = online;
							continue;
						}
						if(selectednum > online && findServer(si.getName()).isJoinable(p)) {
							selected = si;
							selectednum = online;
							continue;
						}
					}
					if(selected == null) {
						pl.getLogger().severe("Could not find ideal server for server/group '"+s.getName()+"'!");
						continue;
					}
					
					p.sendMessage(msgs.getBC("status.sending-now", "SERVER:"+pl.aliases.getAlias(name)));
					p.connect(selected);
				}
				return;
			}
			
			ProxiedPlayer nextplayer = s.getQueue().get(0);
			
			if(!s.canAccess(nextplayer)) continue;
			
			while(nextplayer.getServer().getInfo().getName().equals(s.getName())) {
				s.getQueue().remove(nextplayer);
				if(s.getQueue().size() <= 0) break;
				nextplayer = s.getQueue().get(0);
			}
			if(s.getQueue().size() <= 0) continue;
			while(!nextplayer.isConnected()) {
				s.getQueue().remove(nextplayer);
				if(s.getQueue().size() <= 0) break;
				nextplayer = s.getQueue().get(0);
			}
			if(s.getQueue().size() <= 0) continue;
			if(s.isFull() && !nextplayer.hasPermission("ajqueue.joinfull")) continue;
			
			nextplayer.sendMessage(Main.formatMessage(msgs.get("status.sending-now").replaceAll("\\{SERVER\\}", pl.aliases.getAlias(name))));
			HashMap<ServerInfo, ServerPing> serverInfos = s.getLastPings();
			ServerInfo selected = null;
			int selectednum = 0;
			for(ServerInfo si : serverInfos.keySet()) {
				ServerPing sp = serverInfos.get(si);
				if(sp == null) continue;
				int online = sp.getPlayers().getOnline();
				if(selected == null) {
					selected = si;
					selectednum = online;
					continue;
				}
				if(selectednum > online && findServer(si.getName()).isJoinable(nextplayer)) {
					selected = si;
					selectednum = online;
					continue;
				}
			}
			if(selected == null) {
				pl.getLogger().severe("Could not find ideal server for server/group '"+s.getName()+"'!");
				continue;
			}
			nextplayer.connect(selected);
		}
	}
	
	/**
	 * Add a player to the queue for a server
	 * @param p The player
	 * @param s The name of the server
	 */
	public void addToQueue(ProxiedPlayer p, String s) {
		QueueServer server = findServer(s);
		if(server == null) {
			p.sendMessage(msgs.getBC("errors.server-not-exist"));
			return;
		}
		
		if(pl.config.getBoolean("joinfrom-server-permission") && !p.hasPermission("ajqueue.joinfrom."+p.getServer().getInfo().getName())) {
			p.sendMessage(msgs.getBC("errors.deny-joining-from-server"));
			return;
		}
		
		if(server.isPaused() && pl.config.getBoolean("prevent-joining-paused")) {
			p.sendMessage(msgs.getBC("errors.cant-join-paused", "SERVER:"+pl.aliases.getAlias(server.getName())));
			return;
		}
		
		if(p.getServer().getInfo().getName().equals(s)) {
			p.sendMessage(msgs.getBC("errors.already-connected"));
			return;
		}
		
		List<QueueServer> beforeQueues = findPlayerInQueue(p);
		if(beforeQueues.size() > 0) {
			if(beforeQueues.contains(server)) {
				p.sendMessage(msgs.getBC("errors.already-queued"));
				return;
			}
			if(!pl.config.getBoolean("allow-multiple-queues")) {
				p.sendMessage(msgs.getBC("status.left-last-queue"));
				for(QueueServer ser : beforeQueues) {
					ser.getQueue().remove(p);
				}
			}
		}
		
		List<ProxiedPlayer> list = server.getQueue();
		if(list.indexOf(p) != -1) {
			int pos = list.indexOf(p)+1;
			int len = list.size();
			p.sendMessage(Main.formatMessage(
					msgs.get("errors.already-queued")
					.replaceAll("\\{POS\\}", pos+"")
					.replaceAll("\\{LEN\\}", len+"")
					));
			return;
		}
		if(pl.isp) {
			Logic.priorityLogic(server.getQueue(), s, p);
		} else {
			if((p.hasPermission("ajqueue.priority") || p.hasPermission("ajqueue.serverpriority."+s)) && list.size() > 0) {
				int i = 0;
				for(ProxiedPlayer ply : list) {
					if(!(ply.hasPermission("ajqueue.priority") || ply.hasPermission("ajqueue.serverpriority."+s))) {
						list.add(i, p);
						break;
					}
					i++;
				}
				if(list.size() == 0) {
					list.add(p);
				}
			} else {
				list.add(p);
			}
		}
		int pos = list.indexOf(p)+1;
		int len = list.size();
		boolean sendInstant = pl.config.getStringList("send-instantly").indexOf(server.getName()) != -1;
		if((list.size() <= 1 || sendInstant) && server.canAccess(p)) {
			sendPlayers(s);
			BaseComponent[] m = msgs.getBC("status.now-in-empty-queue", "POS:"+pos, "LEN:"+len, "SERVER:"+pl.aliases.getAlias(s));
			if(TextComponent.toPlainText(m).length() > 0) {
				p.sendMessage(m);
			}
		} else {
			p.sendMessage(Main.formatMessage(
					msgs.get("status.now-in-queue")
					.replaceAll("\\{POS\\}", pos+"")
					.replaceAll("\\{LEN\\}", len+"")
					.replaceAll("\\{SERVER\\}", pl.aliases.getAlias(s))
					));
		}
		
		BungeeUtils.sendCustomData(p, "position", pos+"");
		BungeeUtils.sendCustomData(p, "positionof", len+"");
		BungeeUtils.sendCustomData(p, "queuename", pl.aliases.getAlias(s));
		BungeeUtils.sendCustomData(p, "inqueue", "true");
	}
	
	/**
	 * Finds which servers the player is queued for
	 * @param p The player to search for
	 * @return The servers the player is queued for.
	 */
	public List<QueueServer> findPlayerInQueue(ProxiedPlayer p) {
		List<QueueServer> srs = new ArrayList<>();
		for(QueueServer s : servers) {
			if(s.getQueue().contains(p)) {
				srs.add(s);
			}
		}
		return srs;
	}
	
	public QueueServer getServer(String name) {
		return findServer(name);
	}
}
