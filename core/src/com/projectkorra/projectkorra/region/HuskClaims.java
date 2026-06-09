package com.projectkorra.projectkorra.region;

import java.util.Optional;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import com.projectkorra.projectkorra.ability.CoreAbility;

import net.william278.huskclaims.api.BukkitHuskClaimsAPI;
import net.william278.huskclaims.claim.Claim;
import net.william278.huskclaims.libraries.cloplib.operation.OperationType;
import net.william278.huskclaims.position.Position;
import net.william278.huskclaims.user.OnlineUser;

public class HuskClaims extends RegionProtectionBase {

    protected BukkitHuskClaimsAPI huskClaimsAPI;

    protected HuskClaims() {
        super("HuskClaims");
        this.huskClaimsAPI = BukkitHuskClaimsAPI.getInstance();
    }

    @Override
    public boolean isRegionProtectedReal(Player player, Location location, CoreAbility ability, boolean igniteAbility, boolean explosiveAbility) {
        final OnlineUser user = huskClaimsAPI.getOnlineUser(player);
        final Position position = huskClaimsAPI.getPosition(location);

        final Optional<Claim> claimOpt = huskClaimsAPI.getClaimAt(position);
        if (claimOpt.isEmpty()) {
            return false; // No claim (wilderness) - allow bending
        }

        // Same concept as GriefPrevention/GriefDefender/Lands:
        // Treat the region as protected (block bending) if the player does NOT
        // have block-break/build/trust permissions at this position.
        return !huskClaimsAPI.isOperationAllowed(user, OperationType.BLOCK_BREAK, position);
    }
}
