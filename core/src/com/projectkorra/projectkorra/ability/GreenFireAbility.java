package com.projectkorra.projectkorra.ability;

import org.bukkit.entity.Player;

import com.projectkorra.projectkorra.Element;

/**
 * Cosmetic fire sub-element. Mirrors {@link BlueFireAbility} but is purely visual:
 * it only recolours firebending particles (see
 * {@link FireAbility#playFirebendingParticles}) and intentionally does NOT modify
 * damage, range or cooldown, so there are no factor methods here.
 */
public abstract class GreenFireAbility extends FireAbility implements SubAbility {

	public GreenFireAbility(final Player player) {
		super(player);
	}

	@Override
	public Class<? extends Ability> getParentAbility() {
		return FireAbility.class;
	}

	@Override
	public Element getElement() {
		return Element.GREEN_FIRE;
	}

}
