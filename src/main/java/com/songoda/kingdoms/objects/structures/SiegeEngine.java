package com.songoda.kingdoms.objects.structures;

import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;

import com.songoda.kingdoms.Kingdoms;
import com.songoda.kingdoms.objects.land.Structure;
import com.songoda.kingdoms.objects.land.StructureType;
import com.songoda.kingdoms.utils.IntervalUtils;

public class SiegeEngine extends Structure {
	
	private final long cooldown;
	private long time;
	
	public SiegeEngine(Location location) {
		this(location, System.currentTimeMillis());
	}
	
	public SiegeEngine(Location location, long time) {
		super(location, StructureType.SIEGE_ENGINE);
		FileConfiguration configuration = Kingdoms.getInstance().getConfig();
		String interval = configuration.getString("structures.siege-engine.cooldown", "60 seconds");
		this.cooldown = IntervalUtils.getInterval(interval);
		this.time = time;
	}
	
	public void resetCooldown() {
		this.time = System.currentTimeMillis();
	}
	
	public boolean isReady() {
		return getCooldownTimeLeft() <= 0;
	}
	
	public long getCooldownTimeLeft() {
		long left = -1;
		long now = System.currentTimeMillis();
		long totalTime = cooldown * 60;
		int r = (int) (now - time) / 1000;
		left = (r - totalTime) * (-1);
		return left / 60;
	}

}
