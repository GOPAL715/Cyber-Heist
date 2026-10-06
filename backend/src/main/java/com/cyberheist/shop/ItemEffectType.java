package com.cyberheist.shop;

/**
 * The kinds of bonus an item or a skill can grant.
 *
 * <p>Shared deliberately between the Phase 4 equipment catalogue and the Phase 5
 * skill tree. Both tables store the same five controlled types with the same
 * meaning, so reusing one enum is what lets
 * {@code PlayerBonusService} add equipment and skill totals together and cap the
 * result once. A second, parallel enum for skills would have meant two
 * aggregation paths that could drift, which is the duplication Phase 5 was told
 * to avoid.
 *
 * <p>The enum name predates Phase 5 and is left alone to avoid churning Phase 4
 * code; the Javadoc records that it now covers both sources.
 */
public enum ItemEffectType {
    MISSION_SPEED,
    EXPERIENCE_BONUS,
    COIN_BONUS,
    ENERGY_EFFICIENCY,
    PUZZLE_BONUS
}