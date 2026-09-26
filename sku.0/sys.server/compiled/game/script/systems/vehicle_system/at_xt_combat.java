package script.systems.vehicle_system;

import script.*;
import script.library.*;

/**
 * AT-XT combat while driven: grant free-target splash blaster and force it as default attack.
 * Must not rely on getRiderId() during OnReceivedItem (often still unset at that moment).
 */
public class at_xt_combat extends script.base_script
{
    public at_xt_combat()
    {
    }

    public static final String AT_XT_BLASTER = "at_xt_vehicle_blaster";
    public static final String VAR_DRIVER = "at_xt.driver";

    public int OnAttach(obj_id self) throws InterruptedException
    {
        return SCRIPT_CONTINUE;
    }

    public int OnInitialize(obj_id self) throws InterruptedException
    {
        return SCRIPT_CONTINUE;
    }

    public int OnReceivedItem(obj_id self, obj_id srcContainer, obj_id transferer, obj_id item) throws InterruptedException
    {
        if (!isIdValid(item) || !isPlayer(item))
        {
            return SCRIPT_CONTINUE;
        }
        // Boarding player is the driver for this single-seat vehicle. Do not wait on getRiderId().
        applyDriverCombat(self, item);
        // Re-apply after engine finishes mount assignment (race-safe).
        dictionary d = new dictionary();
        d.put("driver", item);
        messageTo(self, "handleAtXtDriverCombat", d, 0.5f, false);
        return SCRIPT_CONTINUE;
    }

    public int handleAtXtDriverCombat(obj_id self, dictionary params) throws InterruptedException
    {
        if (params == null)
        {
            return SCRIPT_CONTINUE;
        }
        obj_id driver = params.getObjId("driver");
        if (!isIdValid(driver) || !exists(driver) || !isPlayer(driver))
        {
            return SCRIPT_CONTINUE;
        }
        obj_id rider = getRiderId(self);
        if (isIdValid(rider) && rider != driver)
        {
            return SCRIPT_CONTINUE;
        }
        applyDriverCombat(self, driver);
        return SCRIPT_CONTINUE;
    }

    public void applyDriverCombat(obj_id vehicle, obj_id driver) throws InterruptedException
    {
        if (!isIdValid(vehicle) || !isIdValid(driver))
        {
            return;
        }
        utils.setScriptVar(driver, combat.DAMAGE_REDIRECT, vehicle);
        utils.setScriptVar(vehicle, VAR_DRIVER, driver);
        grantCommand(driver, AT_XT_BLASTER);
        overrideDefaultAttack(driver, AT_XT_BLASTER);
    }

    public void clearDriverCombat(obj_id vehicle, obj_id driver) throws InterruptedException
    {
        if (!isIdValid(driver))
        {
            return;
        }
        removeDefaultAttackOverride(driver);
        utils.removeScriptVar(driver, combat.DAMAGE_REDIRECT);
        revokeCommand(driver, AT_XT_BLASTER);
        if (isIdValid(vehicle))
        {
            utils.removeScriptVar(vehicle, VAR_DRIVER);
        }
    }

    public int OnLostItem(obj_id self, obj_id destContainer, obj_id transferer, obj_id item) throws InterruptedException
    {
        if (!isIdValid(item) || !isPlayer(item))
        {
            return SCRIPT_CONTINUE;
        }
        clearDriverCombat(self, item);
        return SCRIPT_CONTINUE;
    }
}
