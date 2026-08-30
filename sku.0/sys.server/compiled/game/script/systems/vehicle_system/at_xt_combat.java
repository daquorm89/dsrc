package script.systems.vehicle_system;

import script.*;
import script.library.*;

/**
 * AT-XT Walker combat: while the owner is driving, left-click / default attack
 * is overridden to at_xt_vehicle_blaster (splash / TARGET_AREA blaster fire).
 * Vehicle itself does not path or fight when unmounted (standard vehicle).
 */
public class at_xt_combat extends script.base_script
{
    public at_xt_combat()
    {
    }
    public static final String AT_XT_BLASTER = "at_xt_vehicle_blaster";

    public int OnAboutToBeTransferred(obj_id self, obj_id destContainer, obj_id transferer) throws InterruptedException
    {
        return SCRIPT_CONTINUE;
    }

    public int OnReceivedItem(obj_id self, obj_id srcContainer, obj_id transferer, obj_id item) throws InterruptedException
    {
        if (!isIdValid(item) || !isPlayer(item))
        {
            return SCRIPT_CONTINUE;
        }
        obj_id driver = getRiderId(self);
        if (isIdValid(driver) && driver == item)
        {
            utils.setScriptVar(item, combat.DAMAGE_REDIRECT, self);
            overrideDefaultAttack(item, AT_XT_BLASTER);
            grantCommand(item, AT_XT_BLASTER);
        }
        return SCRIPT_CONTINUE;
    }

    public int OnLostItem(obj_id self, obj_id destContainer, obj_id transferer, obj_id item) throws InterruptedException
    {
        if (!isIdValid(item) || !isPlayer(item))
        {
            return SCRIPT_CONTINUE;
        }
        removeDefaultAttackOverride(item);
        utils.removeScriptVar(item, combat.DAMAGE_REDIRECT);
        revokeCommand(item, AT_XT_BLASTER);
        return SCRIPT_CONTINUE;
    }
}
