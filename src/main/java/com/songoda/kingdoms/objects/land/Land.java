package com.songoda.kingdoms.objects.land;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Optional;
import java.util.Set;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;

import com.songoda.kingdoms.Kingdoms;
import com.songoda.kingdoms.manager.managers.KingdomManager;
import com.songoda.kingdoms.manager.managers.LandManager;
import com.songoda.kingdoms.objects.kingdom.OfflineKingdom;
import com.songoda.kingdoms.objects.structures.Structure;
import com.songoda.kingdoms.objects.turrets.Turret;

public class Land {

	private final Set<ChestSign> signs = new HashSet<>();
	private final Set<Turret> turrets = new HashSet<>();
	protected transient OfflineKingdom kingdomCache;
	private final KingdomManager kingdomManager;
	private final LandManager landManager;
	private final Kingdoms instance;
	private Structure structure;
	private final Chunk chunk;
	private String kingdom;
	private long claimTime;

	public Land(Chunk chunk) {
		this.chunk = chunk;
		this.instance = Kingdoms.getInstance();
		this.landManager = instance.getManager("land", LandManager.class);
		this.kingdomManager = instance.getManager("kingdom", KingdomManager.class);
	}

	public int getX() {
		return chunk.getX();
	}

	public int getZ() {
		return chunk.getZ();
	}

	public Chunk getChunk() {
		return chunk;
	}

	public World getWorld() {
		return chunk.getWorld();
	}

	public Long getClaimTime() {
		return claimTime;
	}

	public void setClaimTime(long claimTime) {
		this.claimTime = claimTime;
	}

	public Structure getStructure() {
		return structure;
	}

	public void setStructure(Structure structure) {
		this.structure = structure;
	}

	public boolean hasOwner() {
		return getKingdomOwner().isPresent();
	}

	public Optional<OfflineKingdom> getKingdomOwner() {
		if (kingdomCache != null)
			return Optional.of(kingdomCache);
		if (kingdom == null)
			return Optional.empty();
		Optional<OfflineKingdom> optional = kingdomManager.getOfflineKingdom(kingdom);
		if (optional.isPresent())
			kingdomCache = optional.get();
		return optional;
	}

	public void setKingdomOwner(String kingdom) {
		this.kingdom = kingdom;
		if (kingdom == null)
			kingdomCache = null;
	}

	public boolean hasTurret(Turret turret) {
		return turrets.contains(turret);
	}

	public void addTurret(Turret turret) {
		turrets.add(turret);
	}

	public void removeTurret(Turret turret) {
		turrets.remove(turret);
	}

	public Set<Turret> getTurrets() {
		return turrets;
	}

	public void addChestSign(ChestSign sign) {
		signs.add(sign);
	}

	public ChestSign getChestSign(Location location) {
		if (location == null)
			return null;
		for (ChestSign sign : signs) {
			Location signLocation = sign.getLocation();
			if (signLocation.equals(location))
				return sign;
			if (signLocation.distance(location) <= 0.9)
				return sign;
		}
		return null;
	}

	public void removeChestSign(Location location) {
		if (location == null)
			return;
		for (Iterator<ChestSign> iterator = signs.iterator(); iterator.hasNext();){
			ChestSign sign = iterator.next();
			Location signLocation = sign.getLocation();
			if (signLocation.equals(location))
				iterator.remove();
			if (signLocation.distance(location) <= 0.9)
				iterator.remove();
		}
	}

	public Set<Land> getSurrounding() {
		Set<Land> lands = new HashSet<>();
		// North
		Chunk north = chunk.getWorld().getChunkAt(chunk.getX(), chunk.getZ() - 9);
		lands.add(landManager.getLand(north));
		// North-East
		Chunk northEast = chunk.getWorld().getChunkAt(chunk.getX() + 9, chunk.getZ() - 9);
		lands.add(landManager.getLand(northEast));
		// East
		Chunk east = chunk.getWorld().getChunkAt(chunk.getX() + 9, chunk.getZ());
		lands.add(landManager.getLand(east));
		// South-East
		Chunk southEast = chunk.getWorld().getChunkAt(chunk.getX() + 9, chunk.getZ() + 9);
		lands.add(landManager.getLand(southEast));
		// South
		Chunk south = chunk.getWorld().getChunkAt(chunk.getX(), chunk.getZ() + 9);
		lands.add(landManager.getLand(south));
		// South-West
		Chunk southWest = chunk.getWorld().getChunkAt(chunk.getX() - 9, chunk.getZ() + 9);
		lands.add(landManager.getLand(southWest));
		// West
		Chunk west = chunk.getWorld().getChunkAt(chunk.getX() - 9, chunk.getZ());
		lands.add(landManager.getLand(west));
		// North-West
		Chunk northWest = chunk.getWorld().getChunkAt(chunk.getX() - 9, chunk.getZ() - 9);
		lands.add(landManager.getLand(northWest));
		return lands;
	}

	public boolean isSignificant() {
		if (!turrets.isEmpty())
			return true;
		if (structure != null)
			return true;
		if (!signs.isEmpty())
			return true;
		if (kingdom != null)
			return true;
		if (claimTime > 0)
			return true;
		return false;
	}

	public Turret getTurret(Location location) {
		if (turrets.isEmpty())
			return null;
		for (Turret turret : turrets) {
			Location turretLocation = turret.getLocation();
			if (turretLocation.equals(location))
				return turret;
			if (turretLocation.distance(location) <= 0.9)
				return turret;
		}
		return null;
	}

}
