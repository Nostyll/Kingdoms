package me.limeglass.kingdoms.manager.managers;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.stream.Collectors;

import me.limeglass.kingdoms.manager.Manager;
import me.limeglass.kingdoms.utils.ListMessageBuilder;
import me.limeglass.kingdoms.utils.MessageBuilder;

public class MasswarManager extends Manager {

	private final SimpleDateFormat format = new SimpleDateFormat("HH'h' mm'm' ss's'");
	private WorldManager worldManager;
	private long start;
	private int time; //in seconds

	public MasswarManager() {
		super("masswar", true);
		format.setTimeZone(TimeZone.getTimeZone("GMT+0"));
		instance.getServer().getScheduler().runTaskTimerAsynchronously(instance, new Runnable() {
			@Override
			public void run() {
				if (time <= -1)
					return;
				if (!isWarOn())
					stopMassWar();
			}
		}, 0, 20 * 60); //1 minute
	}

	@Override
	public void initalize() {
		this.worldManager = instance.getManager("world", WorldManager.class);
	}

	@Override
	public void onDisable() {
		stopMassWar();
	}

	public boolean isWarOn() {
		return getTimeLeft() > 0 ? true : false;
	}

	public long getTimeLeft() {
		if (time == -1)
			return -1;
		return (start + time * 1000L) - System.currentTimeMillis();
	}

	public String getTimeLeftInString() {
		if (time == -1)
			return new MessageBuilder(false, "masswar.not-on").get();
		Date date = new Date(getTimeLeft() < 0 ? 0 : getTimeLeft());
		return format.format(date)+" left.";
	}

	public void startWar(int time) {
		this.time = time;
		new ListMessageBuilder("masswar.start")
				.replace("%time%", time / 60)
				.toPlayers(instance.getServer().getOnlinePlayers().parallelStream()
						.filter(player -> worldManager.acceptsWorld(player.getWorld()))
						.collect(Collectors.toList()))
				.send();
		start = System.currentTimeMillis();
	}

	public void stopMassWar() {
		if (time >= 0) {
			new ListMessageBuilder("masswar.end")
			.replace("%time%", time / 60)
			.toPlayers(instance.getServer().getOnlinePlayers().parallelStream()
					.filter(player -> worldManager.acceptsWorld(player.getWorld()))
					.collect(Collectors.toList()))
			.send();
		}
		time = -1;
	}

}