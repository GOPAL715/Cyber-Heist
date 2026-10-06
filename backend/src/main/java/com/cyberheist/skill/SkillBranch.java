package com.cyberheist.skill;

/**
 * Which branch a skill belongs to.
 *
 * <p>The four branches are the shapes of player the tree is meant to support:
 * go fast, understand more, survive longer, or reach further. They are a closed
 * set because the value is CHECK-constrained in the database and because the
 * skill screen renders one column per constant - a player cannot invent a
 * branch, and adding one later is an enum constant plus a new constraint in a
 * new migration.
 */
public enum SkillBranch {
    SPEED,
    INTELLIGENCE,
    DEFENSE,
    NETWORK
}