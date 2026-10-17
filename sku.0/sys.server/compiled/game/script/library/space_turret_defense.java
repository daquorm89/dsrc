package script.library;

import script.dictionary;
import script.library.space_transition;
import script.library.space_utils;
import script.library.utils;
import script.obj_id;
import script.string_id;
import script.location;
import script.transform;
import script.vector;

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
    public static final float DURATION_SEC = 120.0f;
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
        obj_id[] targets = pickHostileTargets(ship, player);
        if (targets != null && targets.length > 0)
        {
            fireAllTurretsAt(ship, targets);
        }
        dictionary params = new dictionary();
        params.put("gen", gen);
        params.put("ship", ship);
        messageTo(player, "turretDefenseTick", params, TICK_SEC, false);
    }

    public static obj_id[] pickHostileTargets(obj_id ship, obj_id player) throws InterruptedException
    {
        java.util.ArrayList<obj_id> list = new java.util.ArrayList<obj_id>();
        obj_id lookAt = getLookAtTarget(player);
        if (isIdValid(lookAt) && exists(lookAt) && lookAt != ship && pvpCanAttack(player, lookAt))
        {
            list.add(lookAt);
        }
        // Who is targeting us
        obj_id[] attackers = ship_ai.unitGetWhoIsTargetingMe(ship);
        if (attackers != null)
        {
            for (obj_id a : attackers)
            {
                if (isIdValid(a) && exists(a) && a != ship && !list.contains(a))
                {
                    list.add(a);
                }
            }
        }
        return list.toArray(new obj_id[0]);
    }

    /**
     * Each POB turret covers a 180 degree hemisphere of the ship: weapon 0 is the upper
     * turret (targets above the hull plane, ship-local y &gt;= 0), weapon 1 is the lower
     * turret (targets below, ship-local y &lt; 0). This stops turrets firing through the hull.
     * Other weapon indices are not limited.
     */
    public static boolean isTargetInTurretArc(obj_id ship, int weaponIndex, obj_id target) throws InterruptedException
    {
        if (weaponIndex != 0 && weaponIndex != 1)
        {
            return true;
        }
        location shipLoc = getWorldLocation(ship);
        location targetLoc = getWorldLocation(target);
        if (shipLoc == null || targetLoc == null)
        {
            return true;
        }
        transform t = getTransform_o2w(ship);
        if (t == null)
        {
            return true;
        }
        vector local = t.rotate_p2l(new vector(targetLoc.x - shipLoc.x, targetLoc.y - shipLoc.y, targetLoc.z - shipLoc.z));
        return weaponIndex == 0 ? local.y >= 0.0f : local.y < 0.0f;
    }

    public static void fireAllTurretsAt(obj_id ship, obj_id[] targets) throws InterruptedException
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
            obj_id target = null;
            for (obj_id candidate : targets)
            {
                if (isTargetInTurretArc(ship, w, candidate))
                {
                    target = candidate;
                    break;
                }
            }
            if (!isIdValid(target))
            {
                continue;
            }
            try
            {
                // weaponIndex for fireShipTurretServer is the weapon index (0-based), not chassis slot.
                // targetedComponent = SHIP lets the normal weighted component roll run and lets damage
                // pass through to the hull. Component 0 is the reactor: it was disabled but the
                // target never died.
                fireShipTurretServer(ship, w, target, space_combat.SHIP, true);
            }
            catch (Exception e)
            {
                // Non-turret weapons are ignored by C++ (DEBUG_FATAL only in debug builds)
            }
        }
    }
}
