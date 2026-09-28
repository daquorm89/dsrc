package script.systems.vehicle_system;

import script.*;
import script.library.*;

/**
 * AT-XT combat while driven.
 * Equips stock heavy_particle_beam (WT_groundTargetting) so the client shows the ground
 * area marker like particle/lightning cannons. NPE on null targetLoc is fixed in combat_base.
 * Default attack stays at_xt_vehicle_blaster (LOCATION + TARGET_AREA splash).
 */
public class at_xt_combat extends script.base_script
{
    public at_xt_combat()
    {
    }

    public static final String AT_XT_BLASTER = "at_xt_vehicle_blaster";
    public static final String GROUND_WEAPON = "object/weapon/ranged/heavy/heavy_particle_beam.iff";
    public static final String VAR_DRIVER = "at_xt.driver";
    public static final String VAR_TEMP_WEAPON = "at_xt.tempWeapon";
    public static final String VAR_PREV_WEAPON = "at_xt.prevWeapon";

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
        applyDriverCombat(self, item);
        dictionary d = new dictionary();
        d.put("driver", item);
        messageTo(self, "handleAtXtDriverCombat", d, 0.5f, false);
        messageTo(self, "handleAtXtDriverCombat", d, 1.5f, false);
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

        // Command first so mob fire works even if weapon create fails.
        grantCommand(driver, AT_XT_BLASTER);
        overrideDefaultAttack(driver, AT_XT_BLASTER);

        obj_id currentWep = getCurrentWeapon(driver);
        if (isIdValid(currentWep) && !utils.hasScriptVar(driver, VAR_PREV_WEAPON) && !hasObjVar(currentWep, "at_xt.temp"))
        {
            utils.setScriptVar(driver, VAR_PREV_WEAPON, currentWep);
        }

        obj_id tempWep = obj_id.NULL_ID;
        if (utils.hasScriptVar(driver, VAR_TEMP_WEAPON))
        {
            tempWep = utils.getObjIdScriptVar(driver, VAR_TEMP_WEAPON);
        }
        if (!isIdValid(tempWep) || !exists(tempWep))
        {
            tempWep = weapons.createWeapon(GROUND_WEAPON, driver, 1.0f);
            if (isIdValid(tempWep))
            {
                setObjVar(tempWep, "at_xt.temp", 1);
                setInvulnerable(tempWep, true);
                utils.setScriptVar(driver, VAR_TEMP_WEAPON, tempWep);
            }
            else
            {
                LOG("at_xt", "weapons.createWeapon(heavy_particle_beam) failed for " + driver);
            }
        }
        if (isIdValid(tempWep) && exists(tempWep) && getCurrentWeapon(driver) != tempWep)
        {
            equip(tempWep, driver);
        }
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

        obj_id tempWep = obj_id.NULL_ID;
        if (utils.hasScriptVar(driver, VAR_TEMP_WEAPON))
        {
            tempWep = utils.getObjIdScriptVar(driver, VAR_TEMP_WEAPON);
            utils.removeScriptVar(driver, VAR_TEMP_WEAPON);
        }
        obj_id prevWep = obj_id.NULL_ID;
        if (utils.hasScriptVar(driver, VAR_PREV_WEAPON))
        {
            prevWep = utils.getObjIdScriptVar(driver, VAR_PREV_WEAPON);
            utils.removeScriptVar(driver, VAR_PREV_WEAPON);
        }
        if (isIdValid(prevWep) && exists(prevWep))
        {
            equip(prevWep, driver);
        }
        if (isIdValid(tempWep) && exists(tempWep))
        {
            destroyObject(tempWep);
        }
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
