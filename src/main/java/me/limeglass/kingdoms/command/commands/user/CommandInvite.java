package me.limeglass.kingdoms.command.commands.user;

import java.util.Optional;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import me.limeglass.kingdoms.Kingdoms;
import me.limeglass.kingdoms.command.AbstractCommand;
import me.limeglass.kingdoms.manager.managers.InviteManager;
import me.limeglass.kingdoms.manager.managers.PlayerManager;
import me.limeglass.kingdoms.manager.managers.InviteManager.PlayerInvite;
import me.limeglass.kingdoms.manager.managers.RankManager.Rank;
import me.limeglass.kingdoms.objects.kingdom.Kingdom;
import me.limeglass.kingdoms.objects.player.KingdomPlayer;
import me.limeglass.kingdoms.placeholders.Placeholder;
import me.limeglass.kingdoms.utils.ListMessageBuilder;
import me.limeglass.kingdoms.utils.MessageBuilder;

public class CommandInvite extends AbstractCommand {

	public CommandInvite() {
		super(false, "invite", "i");
	}

	@Override
	protected ReturnType runCommand(Kingdoms instance, CommandSender sender, String... arguments) {
		Player player = (Player) sender;
		KingdomPlayer kingdomPlayer = instance.getManager(PlayerManager.class).getKingdomPlayer(player);
		Kingdom kingdom = kingdomPlayer.getKingdom();
		if (kingdom == null) {
			new MessageBuilder("commands.invite.no-kingdom")
					.setPlaceholderObject(kingdomPlayer)
					.send(player);
			return ReturnType.FAILURE;
		}
		if (arguments.length == 0)
			return ReturnType.SYNTAX_ERROR;
		if (kingdom.getMemberSize() >= kingdom.getMaxMembers()) {
			new MessageBuilder("commands.invite.member-squad-full")
					.setPlaceholderObject(kingdomPlayer)
					.send(player);
			return ReturnType.FAILURE;
		}
		if (!kingdom.getPermissions(kingdomPlayer.getRank()).canInvite()) {
			new MessageBuilder("commands.invite.rank-too-low-invite")
					.withPlaceholder(kingdom.getLowestRankFor(rank -> rank.canInvite()), new Placeholder<Optional<Rank>>("%rank%") {
						@Override
						public String replace(Optional<Rank> rank) {
							if (rank.isPresent())
								return rank.get().getName();
							return "(Not attainable)";
						}
					})
					.setKingdom(kingdom)
					.send(player);
			return ReturnType.FAILURE;
		}
		String name = arguments[0];
		Player find = Bukkit.getPlayer(name);
		if (find == null) {
			new MessageBuilder("commands.invite.player-not-online")
					.setPlaceholderObject(kingdomPlayer)
					.replace("%input%", name)
					.send(player);
			return ReturnType.FAILURE;
		}
		KingdomPlayer target = instance.getManager(PlayerManager.class).getKingdomPlayer(find);
		if (target.getKingdom() != null) {
			new MessageBuilder("commands.invite.player-in-another-kingdom")
					.setPlaceholderObject(kingdomPlayer)
					.replace("%input%", name)
					.send(player);
			return ReturnType.FAILURE;
		}
		InviteManager inviteManager = instance.getManager(InviteManager.class);
		Optional<PlayerInvite> invite = inviteManager.getInvite(target);
		if (invite.isPresent()) {
			new MessageBuilder("commands.invite.invited-already")
					.setPlaceholderObject(kingdomPlayer)
					.replace("%input%", name)
					.send(player);
			return ReturnType.FAILURE;
		}
		boolean already = inviteManager.addInvite(target, kingdom);
		if (already) {
			new MessageBuilder("commands.invite.already")
					.setPlaceholderObject(kingdomPlayer)
					.replace("%input%", name)
					.send(kingdomPlayer.getKingdom().getOnlinePlayers());
			return ReturnType.FAILURE;
		}
		new MessageBuilder("commands.invite.invited")
				.setPlaceholderObject(kingdomPlayer)
				.replace("%input%", name)
				.send(kingdomPlayer.getKingdom().getOnlinePlayers());
		new ListMessageBuilder("commands.invite.invite")
				.setPlaceholderObject(kingdomPlayer)
				.replace("%input%", name)
				.send(target);
		return ReturnType.SYNTAX_ERROR;
	}

	@Override
	public String getConfigurationNode() {
		return "invite";
	}

	@Override
	public String[] getPermissionNodes() {
		return new String[] {"kingdoms.invite", "kingdoms.player"};
	}

}