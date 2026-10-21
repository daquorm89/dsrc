package script.library;

import script.*;

/**
 * Stationed ("set loose") droids.
 *
 * A called-out droid can be turned into a persisted, autonomous NPC that stays where the
 * owner put it (stand / wander / guard) and frees its datapad slot. The owner can pick it
 * up again, which returns it to the datapad.
 *
 * KEY DESIGN FACT: a droid's inventory, datapad (program chips), power level and stored
 * damage live on its pet control device (PCD), not on the droid creature. So the PCD is
 * never destroyed. While the droid is loose the PCD is PARKED inside the droid's own
 * (otherwise unused) inventory container and its scripts are detached, because
 * ai.pet_control_device.OnTransferred destroys the linked droid whenever the PCD moves.
 *
 * Droid-side state (all persisted objvars on the droid):
 *   stationed.owner       obj_id   owning player
 *   stationed.mode        int      MODE_*
 *   stationed.pcd         obj_id   the parked PCD
 *   stationed.pcdScripts  String[] scripts detached from the PCD (re-attached on pick up)
 * Player-side: stationed.droids obj_id[] (registry used for the per-player cap).
 */
public class stationed_droid extends script.base_script
{
    public stationed_droid()
    {
    }
    public static final String SCRIPT = "ai.stationed_droid";
    public static final String STRING_FILE = "pet/pet_menu";

    public static final String OBJVAR_OWNER = "stationed.owner";
    public static final String OBJVAR_MODE = "stationed.mode";
    public static final String OBJVAR_PCD = "stationed.pcd";
    public static final String OBJVAR_PCD_SCRIPTS = "stationed.pcdScripts";
    public static final String OBJVAR_HOME = "stationed.home";
    public static final String OBJVAR_PLAYER_LIST = "stationed.droids";

    public static final int MODE_STAND = 0;
    public static final int MODE_WANDER = 1;
    public static final int MODE_GUARD = 2;

    // Scripts that make the PCD act on the droid; detached while parked.
    public static final String[] PCD_SCRIPTS = { "ai.pet_control_device", "ai.pcd_ping_response" };
    // Scripts that make the droid behave as a follower pet; detached while loose.
    public static final String[] PET_SCRIPTS = { "ai.pet_ping", "ai.pet" };

    public static final int MAX_LOOSE_DROIDS_PER_PLAYER = 10;
    // Ships/POB interiors are packed and unpacked by space_transition; what that does to a
    // persisted NPC in a cell is UNVERIFIED, so this is off until tested in game.
    public static final boolean ALLOW_IN_SHIPS = false;

    public static final float WANDER_MIN = 0.0f;
    public static final float WANDER_MAX = 8.0f;
    public static final float GUARD_RANGE = 20.0f;
    public static final float GUARD_TICK_SEC = 3.0f;
    public static final float INCAP_RECOVER_SEC = 45.0f;

    public static string_id sid(String key)
    {
        return new string_id(STRING_FILE, key);
    }

    public static boolean isStationed(obj_id droid) throws InterruptedException
    {
        return isIdValid(droid) && exists(droid) && hasObjVar(droid, OBJVAR_OWNER);
    }

    public static obj_id getLooseOwner(obj_id droid) throws InterruptedException
    {
        if (!isIdValid(droid) || !hasObjVar(droid, OBJVAR_OWNER))
        {
            return null;
        }
        return getObjIdObjVar(droid, OBJVAR_OWNER);
    }

    public static int getMode(obj_id droid) throws InterruptedException
    {
        if (!isIdValid(droid) || !hasObjVar(droid, OBJVAR_MODE))
        {
            return MODE_STAND;
        }
        return getIntObjVar(droid, OBJVAR_MODE);
    }

    public static obj_id getParkedPcd(obj_id droid) throws InterruptedException
    {
        if (!isIdValid(droid) || !hasObjVar(droid, OBJVAR_PCD))
        {
            return null;
        }
        return getObjIdObjVar(droid, OBJVAR_PCD);
    }

    // ---- per-player registry (cap) -------------------------------------------------
    // Entries are NOT validated with exists(): a droid on another planet server is not
    // "loaded" here but still belongs to the player. Entries leave the list only on
    // pick up or when the droid object is destroyed (ai.stationed_droid.OnDestroy).

    public static obj_id[] getOwnedDroids(obj_id player) throws InterruptedException
    {
        if (!isIdValid(player) || !hasObjVar(player, OBJVAR_PLAYER_LIST))
        {
            return new obj_id[0];
        }
        obj_id[] list = getObjIdArrayObjVar(player, OBJVAR_PLAYER_LIST);
        return list == null ? new obj_id[0] : list;
    }

    public static int countOwnedDroids(obj_id player) throws InterruptedException
    {
        return getOwnedDroids(player).length;
    }

    public static void registerDroid(obj_id player, obj_id droid) throws InterruptedException
    {
        if (!isIdValid(player) || !isIdValid(droid))
        {
            return;
        }
        obj_id[] list = getOwnedDroids(player);
        for (obj_id id : list)
        {
            if (id == droid)
            {
                return;
            }
        }
        obj_id[] next = new obj_id[list.length + 1];
        System.arraycopy(list, 0, next, 0, list.length);
        next[list.length] = droid;
        setObjVar(player, OBJVAR_PLAYER_LIST, next);
    }

    public static void unregisterDroid(obj_id player, obj_id droid) throws InterruptedException
    {
        if (!isIdValid(player) || !isIdValid(droid))
        {
            return;
        }
        obj_id[] list = getOwnedDroids(player);
        int keep = 0;
        for (obj_id id : list)
        {
            if (id != droid)
            {
                keep++;
            }
        }
        if (keep == list.length)
        {
            return;
        }
        if (keep == 0)
        {
            removeObjVar(player, OBJVAR_PLAYER_LIST);
            return;
        }
        obj_id[] next = new obj_id[keep];
        int i = 0;
        for (obj_id id : list)
        {
            if (id != droid)
            {
                next[i++] = id;
            }
        }
        setObjVar(player, OBJVAR_PLAYER_LIST, next);
    }

    // ---- gate ----------------------------------------------------------------------

    /**
     * Returns null when the called-out droid may be set loose by this player, otherwise the
     * string explaining why not.
     */
    public static string_id getSetLooseBlocker(obj_id droid, obj_id player) throws InterruptedException
    {
        if (!isIdValid(droid) || !exists(droid) || !isIdValid(player) || !exists(player))
        {
            return sid("stationed_failed");
        }
        if (isStationed(droid) || getMaster(droid) != player || !pet_lib.isDroidPet(droid))
        {
            return sid("stationed_failed");
        }
        obj_id pcd = callable.getCallableCD(droid);
        if (!isIdValid(pcd) || !exists(pcd) || pet_lib.isNewPlayerHelperDroid(droid))
        {
            return sid("stationed_cant_here");
        }
        if (ai_lib.isInCombat(player) || ai_lib.isInCombat(droid) || ai_lib.isAiDead(droid))
        {
            return sid("stationed_cant_combat");
        }
        if (hasObjVar(pcd, "module_data.maint_run_time") && getGameTime() < getIntObjVar(pcd, "module_data.maint_run_time"))
        {
            return sid("stationed_cant_here");
        }
        if (isSpaceScene())
        {
            return sid("stationed_cant_here");
        }
        if (!ALLOW_IN_SHIPS && isIdValid(space_transition.getContainingShip(player)))
        {
            return sid("stationed_cant_here");
        }
        obj_id inv = utils.getInventoryContainer(droid);
        if (!isIdValid(inv) || !exists(inv))
        {
            return sid("stationed_failed");
        }
        if (countOwnedDroids(player) >= MAX_LOOSE_DROIDS_PER_PLAYER)
        {
            return sid("stationed_too_many");
        }
        return null;
    }
}
