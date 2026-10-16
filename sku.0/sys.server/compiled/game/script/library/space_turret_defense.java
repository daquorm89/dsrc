package script.library;

import script.dictionary;
import script.library.space_transition;
import script.library.space_utils;
import script.library.utils;
import script.obj_id;
import script.string_id;

/**
 * POB droid program: AI-controlled turrets engage hostiles for a limited time.
 * Started by droidcommand_turretdefense -> combat_ship_player.startTurretDefense.
 */
public class space_turret_defense extends script.base_script
{
    public space_turret_defense()
    {
    }

    public static final String LV_GEN = "turretDef.gen";
    public static final String LV_UNTIL = "turretDef.until";
    public static final float DURATION_SEC = 45.0f;
    public static final float TICK_SEC = 1.5f;
    public static final float MAX_RANGE = 500.0f;

    public static void start(obj_id player, obj_id ship) throws InterruptedException
    {
        if (!isIdValid(player) || !isIdValid(ship) || !exists(ship))
        {
            return;
        }
        if (!space_utils.isShipWithInterior(ship))
        {
            sendSystemMessageTestingOnly(player, "Turret Defense requires a ship with an interior (POB).");
            return;
        }
        int gen = utils.getIntLocalVar(ship, LV_GEN) + 1;
        utils.setLocalVar(ship, LV_GEN, gen);
        int until = getGameTime() + (int)DURATION_SEC;
        utils.setLocalVar(ship, LV_UNTIL, until);
        sendSystemMessageTestingOnly(player, "Turret Defense online for " + (int)DURATION_SEC + " seconds.");
        dictionary params = new dictionary();
        params.put("gen", gen);
        params.put("ship", ship);
        messageTo(player, "turretDefenseTick", params, TICK_SEC, false);
    }

    public static void tick(obj_id player, obj_id ship, int gen) throws InterruptedException
    {
        if (!isIdValid(player) || !isIdValid(ship) || !exists(ship) || !exists(player))
        {
            return;
        }
        if (utils.getIntLocalVar(ship, LV_GEN) != gen)
        {
            return;
        }
        if (getGameTime() >= utils.getIntLocalVar(ship, LV_UNTIL))
        {
            sendSystemMessageTestingOnly(player, "Turret Defense offline.");
            return;
        }
        obj_id containing = space_transition.getContainingShip(player);
        if (containing != ship)
        {
            sendSystemMessageTestingOnly(player, "Turret Defense cancelled (left ship).");
            return;
        }
        obj_id target = pickHostileTarget(ship, player);
        if (isIdValid(target))
        {
            fireAllTurretsAt(ship, target);
        }
        dictionary params = new dictionary();
        params.put("gen", gen);
        params.put("ship", ship);
        messageTo(player, "turretDefenseTick", params, TICK_SEC, false);
    }

    public static obj_id pickHostileTarget(obj_id ship, obj_id player) throws InterruptedException
    {
        obj_id lookAt = getLookAtTarget(player);
        if (isIdValid(lookAt) && lookAt != ship && pvpCanAttack(player, lookAt))
        {
            return lookAt;
        }
        // Who is targeting us
        obj_id[] attackers = ship_ai.unitGetWhoIsTargetingMe(ship);
        if (attackers != null)
        {
            for (obj_id a : attackers)
            {
                if (isIdValid(a) && exists(a) && a != ship)
                {
                    return a;
                }
            }
        }
        return null;
    }

    public static void fireAllTurretsAt(obj_id ship, obj_id target) throws InterruptedException
    {
        // Weapon slots: SCST_weapon_0 .. SCST_weapon_last. Pilot guns are often 0;
        // POB gunner turrets are typically higher indices and isTurret on the C++ side.
        for (int w = 0; w < 16; w++)
        {
            int slot = ship_chassis_slot_type.SCST_weapon_first + w;
            if (!isShipSlotInstalled(ship, slot))
            {
                continue;
            }
            if (isShipComponentDisabled(ship, slot))
            {
                continue;
            }
            try
            {
                // weaponIndex for fireShipTurretServer is the weapon index (0-based), not chassis slot
                fireShipTurretServer(ship, w, target, 0, true);
            }
            catch (Exception e)
            {
                // Non-turret weapons are ignored by C++ (DEBUG_FATAL only in debug builds)
            }
        }
    }
}
