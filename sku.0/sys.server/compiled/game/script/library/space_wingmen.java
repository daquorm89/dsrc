package script.library;

import script.dictionary;
import script.obj_id;
import script.transform;
import script.vector;

import java.util.Vector;

/**
 * Player droid-program wingmen (P11). Three escort fighters per program tier,
 * chosen by the pilot's track (Freelancer / Alliance / Imperial).
 *
 * State lives on the pilot as local vars:
 *   wingmen.squadId       squad id of the current wingmen
 *   wingmen.tickGen       generation counter, invalidates stale tick messages
 *   wingmen.nextCallTime  game time when the program may be run again
 *   wingmen.lastTarget    last primary target given to the squad
 *   wingmen.lastAssign    game time of the last target assignment
 */
public class space_wingmen extends script.base_script
{
    public space_wingmen()
    {
    }
    public static final String COMMAND_PREFIX = "droidcommand_wingmen";
    public static final String[] TIER_SUFFIX = 
    {
        "one",
        "two",
        "three",
        "four"
    };
    public static final String SQUAD_PREFIX = "squad_plyr_wingmen_";
    public static final String LV_SQUAD_ID = "wingmen.squadId";
    public static final String LV_TICK_GEN = "wingmen.tickGen";
    public static final String LV_NEXT_CALL = "wingmen.nextCallTime";
    public static final String LV_LAST_TARGET = "wingmen.lastTarget";
    public static final String LV_LAST_ASSIGN = "wingmen.lastAssign";
    public static final float TICK_SECONDS = 3.0f;
    public static final int CALL_COOLDOWN_SECONDS = 20;
    public static final int RETARGET_ON_HIT_SECONDS = 5;
    public static final float SPAWN_BEHIND_DISTANCE = 150.0f;
    public static final float SPAWN_SPREAD = 60.0f;
    public static final float FOLLOW_DISTANCE = 60.0f;
    public static int getTierFromCommand(String strCommand) throws InterruptedException
    {
        if (strCommand == null || !strCommand.startsWith(COMMAND_PREFIX))
        {
            return 0;
        }
        String suffix = strCommand.substring(COMMAND_PREFIX.length());
        for (int i = 0; i < TIER_SUFFIX.length; i++)
        {
            if (TIER_SUFFIX[i].equals(suffix))
            {
                return i + 1;
            }
        }
        return 0;
    }
    public static String getTrack(obj_id player) throws InterruptedException
    {
        if (space_flags.isImperialPilot(player))
        {
            return "imperial";
        }
        if (space_flags.isRebelPilot(player))
        {
            return "rebel";
        }
        return "freelancer";
    }
    public static String getSquadName(String track, int tier) throws InterruptedException
    {
        return SQUAD_PREFIX + track + "_" + tier;
    }
    public static boolean hasWingmen(obj_id player) throws InterruptedException
    {
        return utils.hasLocalVar(player, LV_SQUAD_ID);
    }
    public static int countWingmen(int squadId, obj_id ignoreUnit) throws InterruptedException
    {
        if (!ship_ai.isSquadIdValid(squadId))
        {
            return 0;
        }
        int alive = 0;
        obj_id[] units = ship_ai.squadGetUnitList(squadId);
        if (units == null)
        {
            return 0;
        }
        for (obj_id unit : units)
        {
            if (!isIdValid(unit) || !exists(unit) || unit == ignoreUnit)
            {
                continue;
            }
            if (hasObjVar(unit, "intCleaningUp") || ship_ai.isShipDead(unit))
            {
                continue;
            }
            alive++;
        }
        return alive;
    }
    public static boolean callWingmen(obj_id player, int tier) throws InterruptedException
    {
        if (tier < 1 || tier > TIER_SUFFIX.length)
        {
            return false;
        }
        obj_id ship = space_transition.getContainingShip(player);
        if (!isIdValid(ship) || !exists(ship))
        {
            return false;
        }
        int now = getGameTime();
        if (utils.hasLocalVar(player, LV_NEXT_CALL) && now < utils.getIntLocalVar(player, LV_NEXT_CALL))
        {
            sendSystemMessage(player, "Your wingmen are still regrouping.", null);
            return false;
        }
        dismissWingmen(player, true);
        String squadName = getSquadName(getTrack(player), tier);
        transform loc = getTransform_o2w(ship);
        vector back = ((loc.getLocalFrameK_p()).normalize()).multiply(-SPAWN_BEHIND_DISTANCE);
        loc = loc.move_p(back);
        Vector<obj_id> members = space_create.createSquadHyperspace(null, squadName, loc, SPAWN_SPREAD, null);
        if (members == null || members.size() == 0)
        {
            sendSystemMessage(player, "Your droid could not raise any wingmen.", null);
            return false;
        }
        int squadId = ship_ai.unitGetSquadId(members.get(0));
        if (!ship_ai.isSquadIdValid(squadId))
        {
            for (obj_id member : members)
            {
                if (isIdValid(member))
                {
                    setObjVar(member, "intCleaningUp", 1);
                    destroyObject(member);
                }
            }
            sendSystemMessage(player, "Your droid could not raise any wingmen.", null);
            return false;
        }
        for (obj_id member : members)
        {
            setObjVar(member, "commanderPlayer", player);
        }
        utils.setLocalVar(player, LV_SQUAD_ID, squadId);
        utils.setLocalVar(player, LV_NEXT_CALL, now + CALL_COOLDOWN_SECONDS);
        ship_ai.squadSetAttackOrders(squadId, ship_ai.ATTACK_ORDERS_RETURN_FIRE);
        ship_ai.squadFollow(squadId, ship, new vector(0.0f, 0.0f, -1.0f), FOLLOW_DISTANCE);
        startTick(player);
        sendSystemMessage(player, "Wingmen deployed.", null);
        return true;
    }
    public static void dismissWingmen(obj_id player, boolean graceful) throws InterruptedException
    {
        if (!utils.hasLocalVar(player, LV_SQUAD_ID))
        {
            return;
        }
        int squadId = utils.getIntLocalVar(player, LV_SQUAD_ID);
        utils.removeLocalVar(player, LV_SQUAD_ID);
        utils.removeLocalVar(player, LV_LAST_TARGET);
        utils.removeLocalVar(player, LV_LAST_ASSIGN);
        utils.setLocalVar(player, LV_TICK_GEN, utils.getIntLocalVar(player, LV_TICK_GEN) + 1);
        if (!ship_ai.isSquadIdValid(squadId))
        {
            return;
        }
        obj_id[] units = ship_ai.squadGetUnitList(squadId);
        if (units == null)
        {
            return;
        }
        for (obj_id unit : units)
        {
            if (!isIdValid(unit) || !exists(unit))
            {
                continue;
            }
            setObjVar(unit, "intCleaningUp", 1);
            setObjVar(unit, "evacuate", 1);
            if (graceful)
            {
                destroyObjectHyperspace(unit);
            }
            else 
            {
                destroyObject(unit);
            }
        }
    }
    public static void startTick(obj_id player) throws InterruptedException
    {
        int generation = utils.getIntLocalVar(player, LV_TICK_GEN) + 1;
        utils.setLocalVar(player, LV_TICK_GEN, generation);
        dictionary params = new dictionary();
        params.put("generation", generation);
        messageTo(player, "wingmenTick", params, TICK_SECONDS, false);
    }
    public static void wingmenTick(obj_id player, int generation) throws InterruptedException
    {
        if (!isIdValid(player) || !exists(player) || !utils.hasLocalVar(player, LV_SQUAD_ID))
        {
            return;
        }
        if (generation != utils.getIntLocalVar(player, LV_TICK_GEN))
        {
            return;
        }
        obj_id ship = space_transition.getContainingShip(player);
        int squadId = utils.getIntLocalVar(player, LV_SQUAD_ID);
        if (!isIdValid(ship) || !exists(ship) || !ship_ai.isSquadIdValid(squadId))
        {
            dismissWingmen(player, false);
            return;
        }
        if (countWingmen(squadId, null) < 1)
        {
            dismissWingmen(player, false);
            sendSystemMessage(player, "Your wingmen have been lost.", null);
            return;
        }
        obj_id target = getLookAtTarget(player);
        if (isValidAssistTarget(ship, target))
        {
            assignTarget(player, squadId, target, false);
        }
        else if (utils.hasLocalVar(player, LV_LAST_TARGET))
        {
            obj_id lastTarget = utils.getObjIdLocalVar(player, LV_LAST_TARGET);
            if (!isIdValid(lastTarget) || !exists(lastTarget) || ship_ai.isShipDead(lastTarget))
            {
                utils.removeLocalVar(player, LV_LAST_TARGET);
                ship_ai.squadSetAttackOrders(squadId, ship_ai.ATTACK_ORDERS_RETURN_FIRE);
            }
        }
        dictionary params = new dictionary();
        params.put("generation", generation);
        messageTo(player, "wingmenTick", params, TICK_SECONDS, false);
    }
    public static boolean isAssistableUnit(obj_id ship, obj_id target) throws InterruptedException
    {
        if (!isIdValid(target) || !exists(target) || target == ship)
        {
            return false;
        }
        if (space_utils.isPlayerControlledShip(target))
        {
            return false;
        }
        if (ship_ai.isShipDead(target) || hasObjVar(target, "commanderPlayer"))
        {
            return false;
        }
        return true;
    }
    public static boolean isValidAssistTarget(obj_id ship, obj_id target) throws InterruptedException
    {
        if (!isAssistableUnit(ship, target))
        {
            return false;
        }
        return ship_ai.isShipAggro(target) || ship_ai.isShipAggroToward(target, ship);
    }
    public static void assignTarget(obj_id player, int squadId, obj_id target, boolean throttle) throws InterruptedException
    {
        int now = getGameTime();
        if (utils.hasLocalVar(player, LV_LAST_TARGET) && utils.getObjIdLocalVar(player, LV_LAST_TARGET) == target)
        {
            return;
        }
        if (throttle && utils.hasLocalVar(player, LV_LAST_ASSIGN) && now < utils.getIntLocalVar(player, LV_LAST_ASSIGN) + RETARGET_ON_HIT_SECONDS)
        {
            return;
        }
        utils.setLocalVar(player, LV_LAST_TARGET, target);
        utils.setLocalVar(player, LV_LAST_ASSIGN, now);
        ship_ai.squadSetAttackOrders(squadId, ship_ai.ATTACK_ORDERS_ATTACK_FREELY);
        ship_ai.squadSetPrimaryTarget(squadId, target);
    }
    public static void onCommanderShipHit(obj_id ship, obj_id attacker) throws InterruptedException
    {
        obj_id pilot = getPilotId(ship);
        if (!isIdValid(pilot) || !utils.hasLocalVar(pilot, LV_SQUAD_ID))
        {
            return;
        }
        if (!isAssistableUnit(ship, attacker))
        {
            return;
        }
        int squadId = utils.getIntLocalVar(pilot, LV_SQUAD_ID);
        if (!ship_ai.isSquadIdValid(squadId))
        {
            return;
        }
        assignTarget(pilot, squadId, attacker, true);
    }
    public static void wingmanDestroyed(obj_id player, obj_id deadWingman) throws InterruptedException
    {
        if (!isIdValid(player) || !exists(player) || !utils.hasLocalVar(player, LV_SQUAD_ID))
        {
            return;
        }
        int squadId = utils.getIntLocalVar(player, LV_SQUAD_ID);
        if (countWingmen(squadId, deadWingman) < 1)
        {
            dismissWingmen(player, false);
            sendSystemMessage(player, "Your wingmen have all been shot down.", null);
        }
        else 
        {
            sendSystemMessage(player, "A wingman has been shot down.", null);
        }
    }
}
