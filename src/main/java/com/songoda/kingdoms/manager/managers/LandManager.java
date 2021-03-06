package com.songoda.kingdoms.manager.managers;

import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import com.songoda.kingdoms.Kingdoms;
import com.songoda.kingdoms.database.Database;
import com.songoda.kingdoms.events.LandClaimEvent;
import com.songoda.kingdoms.events.LandLoadEvent;
import com.songoda.kingdoms.events.LandUnclaimEvent;
import com.songoda.kingdoms.events.PlayerChangeChunkEvent;
import com.songoda.kingdoms.manager.Manager;
import com.songoda.kingdoms.manager.managers.RankManager.Rank;
import com.songoda.kingdoms.manager.managers.external.CitizensManager;
import com.songoda.kingdoms.manager.managers.external.DynmapManager;
import com.songoda.kingdoms.manager.managers.external.WorldGuardManager;
import com.songoda.kingdoms.objects.kingdom.Kingdom;
import com.songoda.kingdoms.objects.kingdom.OfflineKingdom;
import com.songoda.kingdoms.objects.land.Land;
import com.songoda.kingdoms.objects.player.KingdomPlayer;
import com.songoda.kingdoms.objects.structures.Structure;
import com.songoda.kingdoms.objects.structures.StructureType;
import com.songoda.kingdoms.placeholders.Placeholder;
import com.songoda.kingdoms.utils.DeprecationUtils;
import com.songoda.kingdoms.utils.IntervalUtils;
import com.songoda.kingdoms.utils.LocationUtils;
import com.songoda.kingdoms.utils.MessageBuilder;
import com.songoda.kingdoms.utils.Utils;

public class LandManager extends Manager {

	private final Map<Chunk, Land> lands = new HashMap<>();
	private final Set<String> forbidden = new HashSet<>();
	private Optional<WorldGuardManager> worldGuardManager;
	private Optional<CitizensManager> citizensManager;
	private Optional<DynmapManager> dynmapManager;
	private VisualizerManager visualizerManager;
	private StructureManager structureManager;
	private KingdomManager kingdomManager;
	private PlayerManager playerManager;
	private NexusManager nexusManager;
	private BukkitTask autoSaveThread;
	private WorldManager worldManager;
	private Database<Land> database;
	private LandManager landManager;

	public LandManager() {
		super(true, "rank");
		this.forbidden.addAll(configuration.getStringList("kingdoms.forbidden-inventories"));
	}

	@Override
	public void initalize() {
		this.worldGuardManager = instance.getExternalManager("worldguard", WorldGuardManager.class);
		this.citizensManager = instance.getExternalManager("citizens", CitizensManager.class);
		this.visualizerManager = instance.getManager(VisualizerManager.class);
		this.dynmapManager = instance.getExternalManager("dynmap", DynmapManager.class);
		this.structureManager = instance.getManager(StructureManager.class);
		this.kingdomManager = instance.getManager(KingdomManager.class);
		this.playerManager = instance.getManager(PlayerManager.class);
		this.worldManager = instance.getManager(WorldManager.class);
		this.nexusManager = instance.getManager(NexusManager.class);
		this.landManager = instance.getManager(LandManager.class);
		String table = configuration.getString("database.land-table", "Lands");
		if (configuration.getBoolean("database.mysql.enabled", false))
			database = getMySQLDatabase(table, Land.class);
		else
			database = getFileDatabase(table, Land.class);
		if (configuration.getBoolean("database.auto-save.enabled")) {
			String interval = configuration.getString("database.auto-save.interval", "5 miniutes");
			autoSaveThread = Bukkit.getScheduler().runTaskTimerAsynchronously(instance, saveTask, 0, IntervalUtils.getInterval(interval) * 20);
		}
		// TODO remove and make async cache.
		Bukkit.getScheduler().runTaskAsynchronously(instance, () -> {
			long total = database.getKeys().parallelStream().map(name -> {
				try {
					Chunk chunk = LocationUtils.stringToChunk(name);
					if (chunk == null)
						return null;
					Land land = database.get(name);
					if (!land.hasOwner())
						Kingdoms.consoleMessage("Land data [" + name + "] is corrupted! ignoring...");
					LandLoadEvent event = new LandLoadEvent(land);
					Bukkit.getPluginManager().callEvent(new LandLoadEvent(land));
					if (!event.isCancelled() && !lands.containsKey(chunk))
						lands.put(chunk, land);
				} catch (Exception e) {
					Kingdoms.consoleMessage("Land data [" + name + "] is corrupted! ignoring...");
					if (instance.getConfig().getBoolean("debug", true))
						e.printStackTrace();
				}
				return name;
			}).count();
			Kingdoms.consoleMessage("&eTotal of [" + total + "] lands were loaded.");
		});
		if (configuration.getBoolean("taxes.enabled", false)) {
			String timeString = configuration.getString("taxes.interval", "2 hours");
			long time = IntervalUtils.getInterval(timeString) * 20;
			int amount = configuration.getInt("taxes.amount", 10);
			Bukkit.getScheduler().scheduleSyncRepeatingTask(instance, new Runnable() {
				@Override
				public void run() {
					Kingdoms.debugMessage("Land taxes executing...");
					new MessageBuilder("taxes.take")
							.toPlayers(Bukkit.getOnlinePlayers())
							.replace("%interval%", timeString)
							.replace("%amount%", amount)
							.send();
					boolean disband = configuration.getBoolean("taxes.disband-cant-afford", false);
					for (Land land : Collections.unmodifiableMap(lands).values()) {
						Optional<OfflineKingdom> optional = land.getKingdomOwner();
						if (!optional.isPresent())
							continue;
						OfflineKingdom kingdom = optional.get();
						long resourcePoints = kingdom.getResourcePoints();
						if (resourcePoints < amount && disband) {
							new MessageBuilder("taxes.disband")
									.toPlayers(Bukkit.getOnlinePlayers())
									.replace("%amount%", amount)
									.setKingdom(kingdom)
									.send();
							kingdomManager.deleteKingdom(kingdom.getName());
							return;
						}
						kingdom.subtractResourcePoints(amount);
						if (configuration.getBoolean("taxes.reverse", false))
							kingdom.addResourcePoints(amount * 2);
					}
				}
			}, time, time);
		}
	}

	private final Runnable saveTask = new Runnable() {
		@Override
		public void run() {
			Set<Entry<Chunk, Land>> loaded = getLoadedLand();
			if (loaded.isEmpty())
				return;
			Kingdoms.debugMessage("Starting Land Save");
			int i = 0;
			Set<String> saved = new HashSet<>();
			for (Entry<Chunk, Land> entry : loaded) {
				if (!entry.getValue().isSignificant())
					continue;
				String name = LocationUtils.chunkToString(entry.getKey());
				if (saved.contains(name))
					continue;
				Kingdoms.debugMessage("Saving land: " + name);
				Land land = entry.getValue();
				Optional<OfflineKingdom> optional = land.getKingdomOwner();
				if (!optional.isPresent() && land.getTurrets().size() <= 0 && land.getStructure() == null) {
					database.delete(name);
					saved.add(name);
					i++;
					continue;
				}
				try{
					database.put(name, land);
					saved.add(name);
					i++;
				} catch (Exception e) {
					Bukkit.getLogger().severe("[Kingdoms] Failed autosave for land at: " + name);
				}
			}
			if (i > 0)
				Kingdoms.debugMessage("Saved [" + i + "] lands");
		}
	};

	public static final class ChunkDaddy {

		private final String world;
		private final int x, z;

		public ChunkDaddy(Chunk chunk) {
			this.world = chunk.getWorld().getName();
			this.x = chunk.getX();
			this.z = chunk.getZ();
		}

		public ChunkDaddy(int x, int z, String world) {
			this.world = world;
			this.x = x;
			this.z = z;
		}

		public String getWorldName() {
			return world;
		}

		public int getX() {
			return x;
		}

		public int getZ() {
			return z;
		}

		@Override
		public boolean equals(Object object) {
			if (!(object instanceof ChunkDaddy))
				return false;
			ChunkDaddy other = (ChunkDaddy) object;
			if (!other.world.equalsIgnoreCase(world))
				return false;
			if (other.x != x || other.z != z)
				return false;
			return true;
		}

	}

	public class LandInfo {

		private final boolean hasKingdom;
		private final String world;
		private final int x, z;
		private String kingdom;

		public LandInfo(Land land) {
			this(land.getChunk(), land.getKingdomName());
		}

		public LandInfo(ChunkDaddy daddy) {
			this(daddy.getX(), daddy.getZ(), daddy.getWorldName(), null);
		}

		public LandInfo(Chunk chunk, String kingdom) {
			this(chunk.getX(), chunk.getZ(), chunk.getWorld().getName(), kingdom);
		}

		public LandInfo(int x, int z, String world, String kingdom) {
			this.hasKingdom = kingdom == null;
			this.kingdom = kingdom;
			this.world = world;
			this.x = x;
			this.z = z;
		}

		public String getKingdomName() {
			return kingdom;
		}

		public boolean hasKingdom() {
			return hasKingdom;
		}

		public World getWorld() {
			return Bukkit.getWorld(world);
		}

		public int getX() {
			return x;
		}

		public int getZ() {
			return z;
		}

		public Land get() {
			return getLand(this);
		}

		public boolean equals(LandInfo other) {
			if (other.hasKingdom != hasKingdom)
				return false;
			if (!other.world.equalsIgnoreCase(world))
				return false;
			if (other.kingdom != null && kingdom != null && !other.kingdom.equalsIgnoreCase(kingdom))
				return false;
			if (other.x != x || other.z != z)
				return false;
			return true;
		}

	}

	public LandInfo getInfo(Land land) {
		return new LandInfo(land);
	}

	/**
	 * If found through means of checking the database.
	 * This way could be used to get the land, but it may not be certain of the Kingdom until getting the Land.
	 * 
	 * @param snapshot The ChunkSnapshot to use.
	 * @return The LandInfo created from the ChunkSnapshot.
	 */
	public LandInfo getInfo(ChunkDaddy daddy) {
		Optional<Land> optional = getLand(daddy);
		if (optional.isPresent())
			return optional.get().toInfo();
		return new LandInfo(daddy);
	}

	/**
	 * @return Set<Chunk> of all loaded land locations.
	 */
	public Set<Entry<Chunk, Land>> getLoadedLand() {
		return Collections.unmodifiableMap(lands).entrySet();
	}

	public Land getLandAt(Location location) {
		return getLand(location.getChunk());
	}

	public Set<Land> getLands(Collection<LandInfo> infos) {
		return infos.parallelStream()
				.map(info -> getLand(info))
				.collect(Collectors.toSet());
	}

	/**
	 * Converts a LandInfo into a Land object, used for lite caching.
	 * 
	 * @param info The LandInfo to read from.
	 * @return The deserialized Land object from the info.
	 */
	public Land getLand(LandInfo info) {
		World world = info.getWorld();
		if (world == null)
			return null;
		Land land = getLand(world.getChunkAt(info.getX(), info.getZ()));
		if (info.hasKingdom())
			land.setKingdomOwner(info.getKingdomName());
		return land;
	}

	/**
	 * Load land if exist; create if not exist.
	 * 
	 * @param chunk Chunk of land to get from.
	 * @return Land even if not loaded.
	 */
	public Land getLand(Chunk chunk) {
		if (chunk == null)
			return null;
		Land land = lands.get(chunk);
		if (land == null) {
			String location = LocationUtils.chunkToString(chunk);
			Kingdoms.debugMessage("Fetching land info for " + location);
			//TODO Could be a future but that failed last time.
			land = database.get(location, new Land(chunk));
			lands.put(chunk, land);
		}
		return land;
	}

	/**
	 * This is only used for async grabbing, it contains no reference of the Kingdom nor Chunk.
	 * 
	 * @param daddy The ChunkDaddy to use for this async get.
	 * @return The Land if found.
	 */
	public Optional<Land> getLand(ChunkDaddy daddy) {
		if (daddy == null)
			return null;
		String location = LocationUtils.daddyToString(daddy);
		return Optional.ofNullable(database.get(location));
	}

	public void playerClaimLand(KingdomPlayer kingdomPlayer, Land land) {
		Player player = kingdomPlayer.getPlayer();
		if (!worldManager.acceptsWorld(player.getWorld())) {
			new MessageBuilder("claiming.world-disabled")
					.setPlaceholderObject(kingdomPlayer)
					.send(player);
			return;
		}
		Kingdom kingdom = kingdomPlayer.getKingdom();
		if (kingdom == null) {
			new MessageBuilder("claiming.no-kingdom")
					.setPlaceholderObject(kingdomPlayer)
					.send(player);
			return;
		}
		Rank rank = kingdomPlayer.getRank();
		if (rank == null) {
			rank = instance.getManager(RankManager.class).getDefaultRank();
			kingdomPlayer.setRank(rank);
		}
		if (!kingdom.getPermissions(rank).canClaim()) {
			new MessageBuilder("kingdoms.permissions-too-low")
					.withPlaceholder(kingdom.getLowestRankFor(r -> r.canClaim()), new Placeholder<Optional<Rank>>("%rank%") {
						@Override
						public String replace(Optional<Rank> rank) {
							if (rank.isPresent())
								return rank.get().getName();
							return "(Not attainable)";
						}
					})
					.setKingdom(kingdom)
					.send(player);
			return;
		}
		if (worldGuardManager.isPresent())
			if (!worldGuardManager.get().canClaim(player.getLocation())) {
				new MessageBuilder("claiming.worldguard").send(player);
				return;
			}
		Chunk chunk = land.getChunk();
		String chunkString = LocationUtils.chunkToString(chunk);
		Optional<OfflineKingdom> optional = land.getKingdomOwner();
		if (optional.isPresent()) {
			OfflineKingdom landKingdom = optional.get();
			if (landKingdom.equals(kingdom)) {
				new MessageBuilder("claiming.already-owned")
						.replace("%chunk%", chunkString)
						.replace("%kingdom%", landKingdom.getName())
						.send(player);
				return;
			}
			new MessageBuilder("claiming.already-claimed")
					.replace("%chunk%", LocationUtils.chunkToString(land.getChunk()))
					.replace("%kingdom%", landKingdom.getName())
					.send(player);
			return;
		}
		Set<Land> claims = kingdomPlayer.getClaims();
		int max = kingdom.getPermissions(kingdomPlayer.getRank()).getMaximumClaims();
		if (max > 0 && claims.size() >= max) {
			new MessageBuilder("claiming.max-user-claims")
					.replace("%amount%", max)
					.setKingdom(kingdom)
					.send(player);
		}
		int maxClaims = configuration.getInt("claiming.maximum-claims", -1);
		if (maxClaims > 0 && kingdom.getClaims().size() >= maxClaims) {
			new MessageBuilder("claiming.max-claims")
					.setKingdom(kingdom)
					.replace("%amount%", maxClaims)
					.send(player);
			return;
		}
		// Check if it's the Kingdoms first claim.
		if (kingdom.getClaims().isEmpty()) {
			new MessageBuilder("claiming.first-claim")
					.replace("%chunk%", chunkString)
					.setKingdom(kingdom)
					.send(player);
			if (kingdom.getSpawn() == null) {
				Location location = player.getLocation();
				kingdom.setSpawn(location);
				kingdom.setUsedFirstClaim(true);
				new MessageBuilder("commands.spawn-set")
						.replace("%location%", LocationUtils.locationToString(location))
						.setKingdom(kingdom)
						.send(player);
			}
			Block block = player.getLocation().getBlock();
			nexusManager.placeNexus(land, block, kingdom, kingdomPlayer);
		} else {
			if (configuration.getBoolean("claiming.land-must-be-connected", false)) {
				boolean connected = false;
				World world = player.getWorld();
				for (int x = -1; x <= 1; x++) {
					for (int z = -1; z <= 1; z++) {
						if (x == 0 && z == 0)
							continue;
						Chunk c = world.getChunkAt(chunk.getX() + x, chunk.getZ() + z);
						Land adjustment = getLand(c);
						Optional<OfflineKingdom> owner = adjustment.getKingdomOwner();
						if (owner.isPresent() && owner.get().equals(kingdom)) {
							connected = true;
							break;
						}
					}
				}
				if (!connected) {
					new MessageBuilder("claiming.must-be-connected")
							.setKingdom(kingdom)
							.send(player);
					return;
				}
			}
			int cost = configuration.getInt("claiming.cost", 5);
			if (!kingdomPlayer.hasAdminMode() && kingdom.getResourcePoints() < cost) {
				new MessageBuilder("claiming.need-resourcepoints")
						.replace("%needed%", cost - kingdom.getResourcePoints())
						.replace("%cost%", cost)
						.setKingdom(kingdom)
						.send(player);
				return;
			} else if (kingdomPlayer.hasAdminMode())
				new MessageBuilder("claiming.admin-claim")
						.replace("%cost%", cost)
						.setKingdom(kingdom)
						.send(player);
			else {
				kingdom.setResourcePoints(kingdom.getResourcePoints() - cost);
				new MessageBuilder("claiming.success")
						.replace("%cost%", cost)
						.setKingdom(kingdom)
						.send(player);
			}
		}
		claimLand(kingdom, landManager.getLand(chunk));
		visualizerManager.visualizeLand(kingdomPlayer, chunk);
	}

	/**
	 * Claim a new land. This does not check if chunk is already occupied.
	 * <p>
	 * This is only used by the API, this does not do any checks.
	 * 
	 * @param chunk Chunk location.
	 * @param kingdom Kingdom owner.
	 */
	public void claimLand(Kingdom kingdom, Land... lands) {
		for (Land land : lands) {
			LandClaimEvent event = new LandClaimEvent(land, kingdom);
			Bukkit.getPluginManager().callEvent(event);
			if (event.isCancelled())
				continue;
			kingdom.addClaim(land);
			land.setClaimTime(new Date().getTime());
			land.setKingdomOwner(kingdom.getName());
			String name = LocationUtils.chunkToString(land.getChunk());
			database.put(name, land);
			kingdom.addUndoClaim(land);
			if (dynmapManager.isPresent())
				dynmapManager.get().update(land.getChunk());
		}
	}

	/**
	 * Unclaim the land. This does not check if chunk is occupied.
	 * 
	 * @param chunk Chunk to unclaim.
	 * @param kingdom Kingdom whom is unclaiming.
	 */
	public void unclaimLand(OfflineKingdom kingdom, Land... lands) {
		for (Land land : lands) {
			Optional<OfflineKingdom> optional = land.getKingdomOwner();
			if (!optional.isPresent())
				continue;
			OfflineKingdom owner = optional.get();
			if (!owner.equals(kingdom))
				continue;
			LandUnclaimEvent event = new LandUnclaimEvent(land, kingdom);
			Bukkit.getPluginManager().callEvent(event);
			if (event.isCancelled())
				continue;
			kingdom.removeClaim(land);
			land.setClaimTime(0L);
			land.setKingdomOwner(null);
			structureManager.breakStructureAt(land);
			database.delete(LocationUtils.chunkToString(land.getChunk()));
			if (configuration.getBoolean("claiming.refund-unclaims", false)) {
				int cost = configuration.getInt("claiming.cost", 5);
				kingdom.addResourcePoints(cost);
			}
			if (land.getStructure() != null)
				structureManager.breakStructureAt(land);
			if (dynmapManager.isPresent())
				dynmapManager.get().update(land.getChunk());
		}
	}

	/**
	 * Unclaim land and checks if there is a kingdom at the land.
	 * 
	 * @param lands The Lands to unclaim
	 */
	public void unclaimLands(Land... lands) {
		for (Land land : lands) {
			Optional<OfflineKingdom> kingdom = land.getKingdomOwner();
			if (!kingdom.isPresent())
				continue;
			unclaimLand(kingdom.get(), land);
		}
	}

	/**
	 * Same as unclaimLands but single land.
	 * 
	 * @param land The Land to unclaim
	 */
	public void unclaimLand(Land land) {
		unclaimLands(land);
	}

	/**
	 * Unclaim ALL existing land in database
	 * Use at own risk.
	 */
	public void unclaimAllExistingLand() {
		kingdomManager.getKingdoms().forEach(kingdom -> unclaimAllLand(kingdom));
	}

	/**
	 * Unclaim all lands that belong to the kingdom.
	 * 
	 * @param kingdom Kingdom owner
	 * @return number of lands unclaimed
	 */
	public int unclaimAllLand(OfflineKingdom kingdom) {
		Set<Land> unclaims = getLoadedLand().stream()
				.map(entry -> entry.getValue())
				.filter(land -> {
					Optional<OfflineKingdom> optional = land.getKingdomOwner();
					if (!optional.isPresent())
						return false;
					return optional.get().equals(kingdom);
				})
				.collect(Collectors.toSet());
		long count = unclaims.size();
		unclaims.forEach(land -> unclaimLand(kingdom, land));
		return (int)count;
	}

	public boolean isConnectedToNexus(Land land) {
		Optional<OfflineKingdom> offlineKingdom = land.getKingdomOwner();
		if (!offlineKingdom.isPresent())
			return false;
		Kingdom kingdom = offlineKingdom.get().getKingdom();
		Location nexusLocation = kingdom.getNexusLocation();
		if (nexusLocation == null)
			return false;
		Land nexus = getLand(nexusLocation.getChunk());
		return getAllConnectingLand(nexus).contains(land);
	}

	/**
	 * Unclaim all lands not connected to the kingdom, and with no structures. TODO It checks for only structures though...
	 * 
	 * @param kingdom Kingdom owner
	 * @return number of lands unclaimed
	 */
	public int unclaimDisconnectedLand(Kingdom kingdom) {
		Set<Land> connected = new HashSet<>();
		getLoadedLand().stream()
				.filter(entry -> entry.getValue().getStructure() != null)
				.filter(entry -> {
					Optional<OfflineKingdom> optional = entry.getValue().getKingdomOwner();
					if (!optional.isPresent())
						return false;
					if (!optional.get().equals(kingdom))
						return false;
					return true;
				})
				.map(entry -> entry.getValue())
				.forEach(land -> connected.addAll(getAllConnectingLand(land)));
		Stream<Land> stream = getLoadedLand().parallelStream()
				.filter(entry -> {
					Optional<OfflineKingdom> optional = entry.getValue().getKingdomOwner();
					if (!optional.isPresent())
						return false;
					if (!optional.get().equals(kingdom))
						return false;
					return true;
				})
				.map(entry -> entry.getValue())
				.filter(land -> !connected.contains(land));
		long count = stream.count();
		stream.forEach(land -> unclaimLand(kingdom, land));
		return (int)count;
	}

	public Set<Land> getConnectingLand(Land center, Collection<Land> checked) {
		return center.getSurrounding().parallelStream()
				.filter(land -> {
					Optional<OfflineKingdom> optional = land.getKingdomOwner();
					if (!optional.isPresent())
						return false;
					Optional<OfflineKingdom> centerOptional = land.getKingdomOwner();
					if (!centerOptional.isPresent())
						return false;
					if (!optional.get().equals(centerOptional.get()))
						return false;
					return true;
				})
				.collect(Collectors.toSet());
	}

	public Set<Land> getOutwardLands(Collection<Land> surroundings, Collection<Land> checked) {
		Set<Land> connected = new HashSet<>();
		for (Land land : surroundings) {
			for (Land furtherSurroundings : land.getSurrounding()) {
				if (surroundings.contains(furtherSurroundings))
					continue;
				if (checked.contains(furtherSurroundings))
					continue;
				if (connected.contains(furtherSurroundings))
					continue;
				connected.add(furtherSurroundings);
			}
		}
		return connected;
	}

	public Set<Land> getAllConnectingLand(Land center) {
		Set<Land> connected = new HashSet<>();
		Set<Land> checked = new HashSet<>();
		connected.add(center);
		Set<Land> outwards = getOutwardLands(getConnectingLand(center, checked), checked);
		boolean check = true;
		while (check) {
			check = false;
			Set<Land> newOutwards = new HashSet<>();
			for (Land land : outwards) {
				Kingdoms.debugMessage("Checking Claim: " + LocationUtils.chunkToString(land.getChunk()));
				if (checked.contains(land))
					continue;
				checked.add(land);
				Optional<OfflineKingdom> landOptional = land.getKingdomOwner();
				if (!landOptional.isPresent())
					continue;
				Optional<OfflineKingdom> centerOptional = land.getKingdomOwner();
				if (!centerOptional.isPresent())
					continue;
				if (!landOptional.get().equals(centerOptional.get()))
					continue;
				connected.add(land);
				newOutwards.add(land);
				check = true;
			}
			outwards = getOutwardLands(newOutwards, checked);
		}
		return connected;
	}

	private boolean isForbidden(Material material) {
		boolean contains = configuration.getBoolean("kingdoms.forbidden-contains", true);
		for (String name : forbidden) {
			if (contains) {
				if (material.name().endsWith(name)) {
					return true;
				}
			} else {
				Material attempt = Utils.materialAttempt(material.name(), "LEGACY_" + material);
				if (attempt != null && attempt == material) {
					return true;
				}
			}
		}
		return false;
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onPressurePlate(PlayerInteractEvent event) {
		if (event.getAction() != Action.PHYSICAL)
			return;
		if (!configuration.getBoolean("kingdoms.other-kingdoms-cannot-pressure-plate", true))
			return;
		Location location = event.getClickedBlock().getLocation();
		World world = location.getWorld();
		if (!worldManager.acceptsWorld(world))
			return;
		Player player = event.getPlayer();
		KingdomPlayer kingdomPlayer = playerManager.getKingdomPlayer(player);
		if (kingdomPlayer.hasAdminMode())
			return;
		if (!kingdomPlayer.hasKingdom()) {
			event.setCancelled(true);
			return;
		}
		Land land = getLand(location.getChunk());
		if (!land.hasOwner())
			return;
		OfflineKingdom landKingdom = land.getKingdomOwner().get();
		if (!landKingdom.equals(kingdomPlayer.getKingdom())) {
			event.setCancelled(true);
			return;
		}
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onKingdomInteract(PlayerInteractEvent event) {
		if (event.getAction() != Action.RIGHT_CLICK_BLOCK)
			return;
		if (Utils.methodExists(PlayerInteractEvent.class, "getHand") && event.getHand() != EquipmentSlot.HAND)
			return;
		Block block = event.getClickedBlock();
		if (block == null)
			return;
		if (configuration.getBoolean("kingdoms.open-other-kingdom-inventories", false))
			return;
		Player player = event.getPlayer();
		// Testing if the player is eating at a block.
		if (player.isSneaking() && !isForbidden(block.getType())) {
			ItemStack item = DeprecationUtils.getItemInMainHand(player);
			if (item == null)
				return;
			if (item.getType() != Material.ARMOR_STAND && item.getType() != Material.ITEM_FRAME) {
				return;
			}
		}
		Location location = block.getLocation();
		Land land = getLand(location.getChunk());
		Optional<OfflineKingdom> optional = land.getKingdomOwner();
		if (!optional.isPresent())
			return;
		OfflineKingdom landKingdom = optional.get();
		KingdomPlayer kingdomPlayer = playerManager.getKingdomPlayer(player);
		if (kingdomPlayer.hasAdminMode())
			return;
		Kingdom kingdom = kingdomPlayer.getKingdom();
		if (kingdom == null) {
			event.setCancelled(true);
			new MessageBuilder("kingdoms.cannot-interact-land-no-kingdom").send(player);
		} else {
			if (!kingdom.equals(landKingdom)) {
				event.setCancelled(true);
				new MessageBuilder("kingdoms.cannot-interact-land")
						.replace("%playerkingdom%", kingdom.getName())
						.setKingdom(landKingdom)
						.send(player);
				return;
			}
			if (!kingdom.getPermissions(kingdomPlayer.getRank()).canBuild()) {
				event.setCancelled(true);
				new MessageBuilder("kingdoms.rank-too-low-build")
						.withPlaceholder(kingdom.getLowestRankFor(rank -> rank.canBuild()), new Placeholder<Optional<Rank>>("%rank%") {
							@Override
							public String replace(Optional<Rank> rank) {
								if (rank.isPresent())
									return rank.get().getName();
								return "(Not attainable)";
							}
						})
						.setKingdom(kingdom)
						.send(player);
				return;
			}
			Structure structure = land.getStructure();
			if (structure != null && structure.getType() == StructureType.NEXUS) {
				if (!kingdom.getPermissions(kingdomPlayer.getRank()).canBuildInNexus()) {
					event.setCancelled(true);
					new MessageBuilder("kingdoms.rank-too-low-nexus-build")
							.withPlaceholder(kingdom.getLowestRankFor(rank -> rank.canBuildInNexus()), new Placeholder<Optional<Rank>>("%rank%") {
								@Override
								public String replace(Optional<Rank> rank) {
									if (rank.isPresent())
										return rank.get().getName();
									return "(Not attainable)";
								}
							})
							.setKingdom(kingdom)
							.send(player);
					return;
				}
			}
		}
	}

	@EventHandler
	public void onEntityInteract(PlayerInteractAtEntityEvent event) {
		Location location = event.getRightClicked().getLocation();
		Land land = getLand(location.getChunk());
		Optional<OfflineKingdom> optional = land.getKingdomOwner();
		if (!optional.isPresent())
			return;
		OfflineKingdom landKingdom = optional.get();
		Player player = event.getPlayer();
		KingdomPlayer kingdomPlayer = playerManager.getKingdomPlayer(player);
		if (kingdomPlayer.hasAdminMode())
			return;
		Kingdom kingdom = kingdomPlayer.getKingdom();
		if (kingdom == null) {
			event.setCancelled(true);
			new MessageBuilder("kingdoms.cannot-interact-land-no-kingdom").send(player);
		} else {
			if (!kingdom.equals(landKingdom)) {
				event.setCancelled(true);
				new MessageBuilder("kingdoms.cannot-interact-land")
						.replace("%playerkingdom%", kingdom.getName())
						.setKingdom(landKingdom)
						.send(player);
				return;
			}
			if (!kingdom.getPermissions(kingdomPlayer.getRank()).canBuild()) {
				event.setCancelled(true);
				new MessageBuilder("kingdoms.rank-too-low-build")
						.withPlaceholder(kingdom.getLowestRankFor(rank -> rank.canClaim()), new Placeholder<Optional<Rank>>("%rank%") {
							@Override
							public String replace(Optional<Rank> rank) {
								if (rank.isPresent())
									return rank.get().getName();
								return "(Not attainable)";
							}
						})
						.setKingdom(kingdom)
						.send(player);
				return;
			}
			Structure structure = land.getStructure();
			if (structure != null && structure.getType() == StructureType.NEXUS) {
				if (!kingdom.getPermissions(kingdomPlayer.getRank()).canBuildInNexus()) {
					event.setCancelled(true);
					new MessageBuilder("kingdoms.rank-too-low-nexus-build")
							.withPlaceholder(kingdom.getLowestRankFor(rank -> rank.canBuildInNexus()), new Placeholder<Optional<Rank>>("%rank%") {
								@Override
								public String replace(Optional<Rank> rank) {
									if (rank.isPresent())
										return rank.get().getName();
									return "(Not attainable)";
								}
							})
							.setKingdom(kingdom)
							.send(player);
					return;
				}
			}
		}
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onChunkChange(PlayerChangeChunkEvent event) {
		Player player = event.getPlayer();
		if (citizensManager.isPresent())
			if (citizensManager.get().isCitizen(player))
				return;
		KingdomPlayer kingdomPlayer = playerManager.getKingdomPlayer(player);
		if (kingdomPlayer.isAutoClaiming()) {
			Bukkit.getScheduler().scheduleSyncDelayedTask(Kingdoms.getInstance(), new Runnable() {
				public void run() {
					playerClaimLand(kingdomPlayer, getLand(player.getLocation().getChunk()));
				}
			}, 1L);
		}
	}

	@EventHandler
	public void onBucketFill(PlayerBucketFillEvent event) {
		Block block = event.getBlockClicked();
		World world = block.getWorld();
		if (!worldManager.acceptsWorld(world))
			return;
		if (worldManager.canBuildInUnoccupied(world))
			return;
		Location location = block.getRelative(event.getBlockFace()).getLocation();
		Land land = getLand(location.getChunk());
		KingdomPlayer kingdomPlayer = playerManager.getKingdomPlayer(event.getPlayer());
		Optional<OfflineKingdom> optional = land.getKingdomOwner();
		if (!optional.isPresent())
			return;
		OfflineKingdom kingdom = optional.get();
		if (!kingdomPlayer.hasAdminMode()) {
			event.setCancelled(true);
			new MessageBuilder("kingdoms.cannot-build-unoccupied-land")
					.setPlaceholderObject(kingdomPlayer)
					.setKingdom(kingdom)
					.send(kingdomPlayer);
		}
	}

	@EventHandler
	public void onBucketEmpty(PlayerBucketEmptyEvent event) {
		Block block = event.getBlockClicked();
		World world = block.getWorld();
		if (!worldManager.acceptsWorld(world))
			return;
		if (worldManager.canBuildInUnoccupied(world))
			return;
		Location location = block.getRelative(event.getBlockFace()).getLocation();
		Land land = getLand(location.getChunk());
		KingdomPlayer kingdomPlayer = playerManager.getKingdomPlayer(event.getPlayer());
		Optional<OfflineKingdom> optional = land.getKingdomOwner();
		if (!optional.isPresent())
			return;
		OfflineKingdom kingdom = optional.get();
		if (!kingdomPlayer.hasAdminMode()) {
			event.setCancelled(true);
			new MessageBuilder("kingdoms.cannot-build-unoccupied-land")
					.setPlaceholderObject(kingdomPlayer)
					.setKingdom(kingdom)
					.send(kingdomPlayer);
		}
	}

	@EventHandler
	public void onBreakUnoccupied(BlockBreakEvent event) {
		if (event.isCancelled())
			return;
		Block block = event.getBlock();
		World world = block.getWorld();
		if (!worldManager.acceptsWorld(world))
			return;
		if (worldManager.canBuildInUnoccupied(world))
			return;
		Location location = block.getLocation();
		Land land = getLand(location.getChunk());
		KingdomPlayer kingdomPlayer = playerManager.getKingdomPlayer(event.getPlayer());
		Optional<OfflineKingdom> optional = land.getKingdomOwner();
		if (!optional.isPresent())
			return;
		OfflineKingdom kingdom = optional.get();
		if (!kingdomPlayer.hasAdminMode()) {
			event.setCancelled(true);
			new MessageBuilder("kingdoms.cannot-build-unoccupied-land")
					.setPlaceholderObject(kingdomPlayer)
					.setKingdom(kingdom)
					.send(kingdomPlayer);
		}
	}

	@EventHandler
	public void onPlaceUnoccupied(BlockPlaceEvent event) {
		if (event.isCancelled())
			return;
		Block block = event.getBlock();
		World world = block.getWorld();
		if (!worldManager.acceptsWorld(world))
			return;
		if (worldManager.canBuildInUnoccupied(world))
			return;
		Location location = block.getLocation();
		Land land = getLand(location.getChunk());
		KingdomPlayer kingdomPlayer = playerManager.getKingdomPlayer(event.getPlayer());
		Optional<OfflineKingdom> optional = land.getKingdomOwner();
		if (!optional.isPresent())
			return;
		OfflineKingdom kingdom = optional.get();
		if (!kingdomPlayer.hasAdminMode()) {
			event.setCancelled(true);
			new MessageBuilder("kingdoms.cannot-build-unoccupied-land")
					.setPlaceholderObject(kingdomPlayer)
					.setKingdom(kingdom)
					.send(kingdomPlayer);
		}
	}

	@EventHandler
	public void onBreakArmorStandOrFrame(EntityDamageByEntityEvent event) {
		if (event.isCancelled())
			return;
		Entity entity = event.getEntity();
		World world = entity.getWorld();
		if (!worldManager.acceptsWorld(world))
			return;
		EntityType type = entity.getType();
		if (type != EntityType.ARMOR_STAND && type != EntityType.ITEM_FRAME)
			return;
		if (!(event.getDamager() instanceof Player))
			return;
		KingdomPlayer kingdomPlayer = playerManager.getKingdomPlayer((Player)event.getDamager());
		if (kingdomPlayer.hasAdminMode())
			return;
		Location location = entity.getLocation();
		Land land = getLand(location.getChunk());
		Optional<OfflineKingdom> optional = land.getKingdomOwner();
		if (!optional.isPresent())
			return;
		OfflineKingdom landKingdom = optional.get();
		if (landKingdom == null)
			return;
		Kingdom kingdom = kingdomPlayer.getKingdom();
		if (kingdom == null){
			event.setCancelled(true);
			new MessageBuilder("kingdoms.cannot-interact-land-no-kingdom").send(kingdomPlayer);
		} else {
			if (!kingdom.equals(landKingdom)) {
				event.setCancelled(true);
				new MessageBuilder("kingdoms.cannot-interact-land")
						.replace("%playerkingdom%", kingdom.getName())
						.setKingdom(landKingdom)
						.send(kingdomPlayer);
				return;
			}
			Structure structure = land.getStructure();
			if (structure != null && structure.getType() == StructureType.NEXUS) {
				if (!kingdom.getPermissions(kingdomPlayer.getRank()).canBuildInNexus()) {
					event.setCancelled(true);
					new MessageBuilder("kingdoms.rank-too-low-nexus-build")
							.withPlaceholder(kingdom.getLowestRankFor(rank -> rank.canBuildInNexus()), new Placeholder<Optional<Rank>>("%rank%") {
								@Override
								public String replace(Optional<Rank> rank) {
									if (rank.isPresent())
										return rank.get().getName();
									return "(Not attainable)";
								}
							})
							.setKingdom(kingdom)
							.send(kingdomPlayer);
					return;
				}
			}
		}
	}

	@EventHandler
	public void onBreakInOtherKingdom(BlockBreakEvent event) {
		if (event.isCancelled())
			return;
		Block block = event.getBlock();
		World world = block.getWorld();
		if (!worldManager.acceptsWorld(world))
			return;
		Location location = block.getLocation();
		Land land = getLand(location.getChunk());
		Optional<OfflineKingdom> optional = land.getKingdomOwner();
		if (!optional.isPresent())
			return;
		OfflineKingdom landKingdom = optional.get();
		if (landKingdom == null)
			return;
		KingdomPlayer kingdomPlayer = playerManager.getKingdomPlayer(event.getPlayer());
		if (kingdomPlayer.hasAdminMode())
			return;
		Kingdom kingdom = kingdomPlayer.getKingdom();
		if (kingdom == null) {
			event.setCancelled(true);
			new MessageBuilder("kingdoms.cannot-interact-land-no-kingdom").send(kingdomPlayer);
		} else {
			if (!kingdom.equals(landKingdom)) {
				event.setCancelled(true);
				new MessageBuilder("kingdoms.cannot-interact-land")
						.replace("%playerkingdom%", kingdom.getName())
						.setKingdom(landKingdom)
						.send(kingdomPlayer);
				return;
			}
			Structure structure = land.getStructure();
			if (structure != null && structure.getType() == StructureType.NEXUS) {
				if (!kingdom.getPermissions(kingdomPlayer.getRank()).canBuildInNexus()) {
					event.setCancelled(true);
					new MessageBuilder("kingdoms.rank-too-low-nexus-build")
							.withPlaceholder(kingdom.getLowestRankFor(rank -> rank.canBuildInNexus()), new Placeholder<Optional<Rank>>("%rank%") {
								@Override
								public String replace(Optional<Rank> rank) {
									if (rank.isPresent())
										return rank.get().getName();
									return "(Not attainable)";
								}
							})
							.setKingdom(kingdom)
							.send(kingdomPlayer);
					return;
				}
			}
		}
	}

	@EventHandler
	public void onBucketOtherFill(PlayerBucketFillEvent event) {
		Location location = event.getBlockClicked().getLocation();
		Land land = getLand(location.getChunk());
		Optional<OfflineKingdom> optional = land.getKingdomOwner();
		if (!optional.isPresent())
			return;
		OfflineKingdom landKingdom = optional.get();
		if (landKingdom == null)
			return;
		KingdomPlayer kingdomPlayer = playerManager.getKingdomPlayer(event.getPlayer());
		if (kingdomPlayer.hasAdminMode())
			return;
		Kingdom kingdom = kingdomPlayer.getKingdom();
		if (kingdom == null) {
			event.setCancelled(true);
			new MessageBuilder("kingdoms.cannot-interact-land-no-kingdom").send(kingdomPlayer);
		} else {
			if (!kingdom.equals(landKingdom)) {
				event.setCancelled(true);
				new MessageBuilder("kingdoms.cannot-interact-land")
						.replace("%playerkingdom%", kingdom.getName())
						.setKingdom(landKingdom)
						.send(kingdomPlayer);
				return;
			}
		}
	}

	@EventHandler
	public void onBucketOtherEmpty(PlayerBucketEmptyEvent event) {
		Location location = event.getBlockClicked().getLocation();
		Land land = getLand(location.getChunk());
		Optional<OfflineKingdom> optional = land.getKingdomOwner();
		if (!optional.isPresent())
			return;
		OfflineKingdom landKingdom = optional.get();
		if (landKingdom == null)
			return;
		KingdomPlayer kingdomPlayer = playerManager.getKingdomPlayer(event.getPlayer());
		if (kingdomPlayer.hasAdminMode())
			return;
		Kingdom kingdom = kingdomPlayer.getKingdom();
		if (kingdom == null) {
			event.setCancelled(true);
			new MessageBuilder("kingdoms.cannot-interact-land-no-kingdom").send(kingdomPlayer);
		} else {
			if (!kingdom.equals(landKingdom)) {
				event.setCancelled(true);
				new MessageBuilder("kingdoms.cannot-interact-land")
						.replace("%playerkingdom%", kingdom.getName())
						.setKingdom(landKingdom)
						.send(kingdomPlayer);
				return;
			}
		}
	}

	@EventHandler
	public void onPlaceInOtherKingdom(BlockPlaceEvent event) {
		Location location = event.getBlock().getLocation();
		Land land = landManager.getLand(location.getChunk());
		Optional<OfflineKingdom> optional = land.getKingdomOwner();
		if (!optional.isPresent())
			return;
		OfflineKingdom landKingdom = optional.get();
		if (landKingdom == null)
			return;
		KingdomPlayer kingdomPlayer = playerManager.getKingdomPlayer(event.getPlayer());
		if (kingdomPlayer.hasAdminMode())
			return;
		Kingdom kingdom = kingdomPlayer.getKingdom();
		if (kingdom == null) {
			event.setCancelled(true);
			new MessageBuilder("kingdoms.cannot-interact-land-no-kingdom").send(kingdomPlayer);
		} else {
			if (!kingdom.equals(landKingdom)) {
				event.setCancelled(true);
				new MessageBuilder("kingdoms.cannot-interact-land")
						.replace("%playerkingdom%", kingdom.getName())
						.setKingdom(landKingdom)
						.send(kingdomPlayer);
				return;
			}
			Structure structure = land.getStructure();
			if (structure != null && structure.getType() == StructureType.NEXUS) {
				if (!kingdom.getPermissions(kingdomPlayer.getRank()).canBuildInNexus()) {
					event.setCancelled(true);
					new MessageBuilder("kingdoms.rank-too-low-nexus-build")
							.withPlaceholder(kingdom.getLowestRankFor(rank -> rank.canBuildInNexus()), new Placeholder<Optional<Rank>>("%rank%") {
								@Override
								public String replace(Optional<Rank> rank) {
									if (rank.isPresent())
										return rank.get().getName();
									return "(Not attainable)";
								}
							})
							.setKingdom(kingdom)
							.send(kingdomPlayer);
					return;
				}
			}
		}
	}

	@EventHandler
	public void onChunkUnload(ChunkUnloadEvent event) {
		Chunk chunk = event.getChunk();
		if (lands.containsKey(chunk)) {
			String name = LocationUtils.chunkToString(chunk);
			Land land = lands.get(chunk);
			if (land.isSignificant())
				database.put(name, lands.get(chunk));
			lands.remove(chunk);
		}
	}

	@EventHandler
	public void onFlowIntoKingdom(BlockFromToEvent event) {
		if (!configuration.getBoolean("kingdoms.disable-liquid-flow-into", false))
			return;
		Optional<OfflineKingdom> to = getLand(event.getToBlock().getLocation().getChunk()).getKingdomOwner();
		if (!to.isPresent())
			return;
		Optional<OfflineKingdom> from = getLand(event.getBlock().getLocation().getChunk()).getKingdomOwner();
		if (!from.isPresent())
			event.setCancelled(true);
		else if (!from.get().equals(to.get()))
			event.setCancelled(true);
	}

	@Override
	public void onDisable() {
		if (autoSaveThread != null)
			autoSaveThread.cancel();
		Kingdoms.debugMessage("Saving lands to database...");
		try {
			saveTask.run();
		} catch (Exception e) {
			Kingdoms.consoleMessage("Saving to database failed.");
			e.printStackTrace();
		}
		lands.clear();
	}

}
