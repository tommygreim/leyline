package leyline.game.snapshot

import forge.card.CardStateName
import forge.card.GamePieceType
import forge.game.ability.ApiType
import forge.game.card.Card
import forge.game.trigger.TriggerType

/** Native identity of the source-less player rule represented by Forge's speed effect. */
internal object SpeedEffectIdentity {
    const val CARD_GRP_ID = 96296
    const val ABILITY_GRP_ID = 355
    const val START_TITLE_ID = 928983
    const val MAX_TITLE_ID = 928985

    // Inspect the original state: reaching max speed changes the effect's face
    // while its increase trigger can still be on the stack.
    fun matches(card: Card): Boolean =
        card.gamePieceType == GamePieceType.EFFECT &&
            card.rules == null &&
            card.getState(CardStateName.Original).triggers.any { trigger ->
                trigger.mode == TriggerType.LifeLostAll &&
                    trigger.getParam("TriggerZones") == "Command" &&
                    trigger.overridingAbility?.api == ApiType.ChangeSpeed
            }
}
