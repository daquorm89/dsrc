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
    // Wander around the post and attack hostile creatures on sight (guard only reacts when
    // the owner or the droid itself is attacked).
    public static final int MODE_PATROL = 3;

    // Scripts that make the PCD act on the droid; detached while parked.
    public static final String[] PCD_SCRIPTS = { "ai.pet_control_device", "ai.pcd_ping_response" };
    // Scripts that make the droid behave as a follower pet; detached while loose.
    public static final String[] PET_SCRIPTS = { "ai.pet_ping", "ai.pet" };

    public static final int MAX_LOOSE_DROIDS_PER_PLAYER = 25;
    // Ships/POB interiors are packed and unpacked by space_transition; what that does to a
    // persisted NPC in a cell is UNVERIFIED in game, but the owner asked for it to be enabled.
    // Set to false to block set loose while aboard a ship again.
    public static final boolean ALLOW_IN_SHIPS = true;

    public static final float WANDER_MIN = 0.0f;
    public static final float WANDER_MAX = 8.0f;
    public static final float PATROL_MAX = 15.0f;
    // Script-driven roaming (engine loiter did not move the droids). The droid walks short hops
    // to spots it can see (line of sight), pauses after each hop, and varies its speed.
    public static final float ROAM_STEP_MIN = 2.0f;
    public static final float ROAM_STEP_MAX = 6.0f;
    // Inside buildings / ship interiors hops are shorter (rooms are small).
    public static final float ROAM_STEP_MAX_INDOOR = 3.5f;
    // Largest height change allowed between two outdoor spots (avoids cliffs / ledges).
    public static final float ROAM_MAX_RISE = 1.5f;
    public static final int ROAM_PICK_TRIES = 8;
    // After this many ticks in a row without any visible spot, take one short blind hop toward
    // the post (safety valve in case line-of-sight tests are unreliable at a location).
    public static final int ROAM_BLIND_AFTER = 6;
    // Pause after reaching a spot; sometimes a longer idle.
    public static final float ROAM_PAUSE_MIN_SEC = 4.0f;
    public static final float ROAM_PAUSE_MAX_SEC = 12.0f;
    public static final float ROAM_LONG_PAUSE_CHANCE = 0.2f;
    public static final float ROAM_LONG_PAUSE_MIN_SEC = 15.0f;
    public static final float ROAM_LONG_PAUSE_MAX_SEC = 30.0f;
    // Retry soon when no reachable spot was found (narrow spaces) or the path was rejected.
    public static final float ROAM_RETRY_MIN_SEC = 1.5f;
    public static final float ROAM_RETRY_MAX_SEC = 3.5f;
    // Casual speed: mostly a walk at 60-100% of walking speed, now and then a slow jog.
    public static final float ROAM_WALK_PCT_MIN = 0.6f;
    public static final float ROAM_WALK_PCT_MAX = 1.0f;
    public static final float ROAM_JOG_CHANCE = 0.15f;
    public static final float ROAM_JOG_PCT_MIN = 0.5f;
    public static final float ROAM_JOG_PCT_MAX = 0.75f;
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

    // ---- script helpers --------------------------------------------------------------

    /** Detaches every listed script that is attached; returns the names that were. */
    private static String[] detachPresent(obj_id obj, String[] scripts) throws InterruptedException
    {
        java.util.Vector gone = new java.util.Vector();
        for (String s : scripts)
        {
            if (hasScript(obj, s))
            {
                detachScript(obj, s);
                gone.add(s);
            }
        }
        return (String[])gone.toArray(new String[0]);
    }

    private static void attachMissing(obj_id obj, String[] scripts) throws InterruptedException
    {
        for (String s : scripts)
        {
            if (!hasScript(obj, s))
            {
                attachScript(obj, s);
            }
        }
    }

    // ---- set loose -------------------------------------------------------------------

    /**
     * Turns the player's called-out droid into a persisted, autonomous NPC. Every step that
     * can fail runs BEFORE anything is detached, and a failed park rolls the droid back into
     * the datapad, so the droid and its control device are never lost.
     */
    public static boolean setLoose(obj_id droid, obj_id player) throws InterruptedException
    {
        string_id why = getSetLooseBlocker(droid, player);
        if (why != null)
        {
            sendSystemMessage(player, why);
            return false;
        }
        obj_id pcd = callable.getCallableCD(droid);
        obj_id inv = utils.getInventoryContainer(droid);

        // The droid must be persisted before the PCD is parked in it, otherwise the PCD
        // would sit in a non-persisted container and be lost on the next restart.
        if (!persistObject(droid) && !isObjectPersisted(droid))
        {
            sendSystemMessage(player, sid("stationed_failed"));
            return false;
        }

        setObjVar(droid, OBJVAR_OWNER, player);
        setObjVar(droid, OBJVAR_MODE, MODE_STAND);
        setObjVar(droid, OBJVAR_PCD, pcd);
        setObjVar(droid, OBJVAR_HOME, getLocation(droid));

        // Flush damage / name / colours into the PCD while it is still linked and loaded.
        pet_lib.savePetInfo(droid, pcd);
        pet_lib.removeActiveDroid(player, droid);

        // Park the PCD. Its scripts must go first: ai.pet_control_device.OnTransferred
        // destroys the linked droid when the PCD moves.
        String[] gone = detachPresent(pcd, PCD_SCRIPTS);
        setObjVar(droid, OBJVAR_PCD_SCRIPTS, gone);
        callable.setCDCallable(pcd, null);
        if (!putIn(pcd, inv))
        {
            // Roll back: PCD stays usable in the datapad and the droid goes back into it.
            attachMissing(pcd, gone);
            callable.setCDCallable(pcd, droid);
            removeObjVar(droid, "stationed");
            sendSystemMessage(player, sid("stationed_failed"));
            pet_lib.storePet(droid, player);
            return false;
        }

        // The droid stops being a follower pet. ai.pet.OnDetach clears the "ai.pet" objvars
        // and the inventory owner; nothing below relies on those.
        detachPresent(droid, PET_SCRIPTS);
        registerDroid(player, droid);
        attachScript(droid, SCRIPT);
        // ai.pet.OnDetach scheduled its own "default calm behaviour" message; apply our mode
        // now so that message finds our objvars instead of briefly letting the droid wander.
        applyMode(droid);
        sendSystemMessage(player, sid("stationed_now_loose"));
        return true;
    }

    // ---- pick up ---------------------------------------------------------------------

    /** Returns a loose droid to its owner's datapad (restoring the PCD) and removes the NPC. */
    public static boolean pickUp(obj_id droid, obj_id player) throws InterruptedException
    {
        if (!isStationed(droid) || !isIdValid(player) || getLooseOwner(droid) != player)
        {
            sendSystemMessage(player, sid("stationed_not_owner"));
            return false;
        }
        if (ai_lib.isInCombat(droid) || ai_lib.isInCombat(player))
        {
            sendSystemMessage(player, sid("stationed_cant_combat"));
            return false;
        }
        obj_id pcd = getParkedPcd(droid);
        obj_id datapad = utils.getPlayerDatapad(player);
        if (!isIdValid(pcd) || !exists(pcd) || !isIdValid(datapad) || !exists(datapad))
        {
            sendSystemMessage(player, sid("stationed_failed"));
            return false;
        }
        if (pet_lib.hasMaxStoredPetsOfType(player, pet_lib.PET_TYPE_DROID))
        {
            sendSystemMessage(player, pet_lib.SID_SYS_TOO_MANY_STORED_PETS);
            return false;
        }
        if (!returnPcdToDatapad(droid, pcd, datapad))
        {
            sendSystemMessage(player, sid("stationed_failed"));
            return false;
        }
        pet_lib.savePetInfo(droid, pcd);

        unregisterDroid(player, droid);
        utils.setScriptVar(droid, "stored", true);
        if (!destroyObject(droid))
        {
            LOG("stationed_droid", "pickUp: failed to destroy droid " + droid + " after returning PCD " + pcd);
        }
        sendSystemMessage(player, sid("stationed_picked_up"));
        return true;
    }

    /** Moves the parked PCD back into the datapad and makes it a normal stored PCD again. */
    private static boolean returnPcdToDatapad(obj_id droid, obj_id pcd, obj_id datapad) throws InterruptedException
    {
        if (!putIn(pcd, datapad))
        {
            return false;
        }
        String[] scripts = hasObjVar(droid, OBJVAR_PCD_SCRIPTS) ? getStringArrayObjVar(droid, OBJVAR_PCD_SCRIPTS) : PCD_SCRIPTS;
        callable.setCDCallable(pcd, null);
        callable.setCallableCD(droid, pcd);
        setObjVar(pcd, "pet.timeStored", getGameTime());
        setCount(pcd, 0);
        attachMissing(pcd, scripts);
        sendDirtyObjectMenuNotification(pcd);
        return true;
    }

    /**
     * Last-resort recovery when a loose droid is about to be destroyed (killed, or removed by
     * some other script) while the PCD is still parked inside it: the PCD would be destroyed
     * with the droid's inventory. Puts the PCD back in the owner's datapad (this server never
     * flags a PCD as permanently dead). Returns true when the PCD is safe.
     */
    public static boolean rescuePcd(obj_id droid) throws InterruptedException
    {
        obj_id pcd = getParkedPcd(droid);
        obj_id owner = getLooseOwner(droid);
        if (!isIdValid(pcd) || !exists(pcd))
        {
            return false;
        }
        obj_id inv = utils.getInventoryContainer(droid);
        if (isIdValid(inv) && getContainedBy(pcd) != inv)
        {
            return true;
        }
        obj_id datapad = isIdValid(owner) ? utils.getPlayerDatapad(owner) : null;
        if (!isIdValid(datapad) || !exists(datapad) || !returnPcdToDatapad(droid, pcd, datapad))
        {
            LOG("stationed_droid", "rescuePcd: could not return PCD " + pcd + " of droid " + droid + " to owner " + owner + "; it will be lost with the droid");
            return false;
        }
        unregisterDroid(owner, droid);
        sendSystemMessage(owner, pet_lib.SID_SYS_PACKED_DROID);
        return true;
    }

    // ---- orders ----------------------------------------------------------------------

    /** Owner command: change the droid's mode. The post becomes wherever the droid stands now. */
    public static boolean setMode(obj_id droid, obj_id player, int mode) throws InterruptedException
    {
        if (!isStationed(droid) || getLooseOwner(droid) != player || mode < MODE_STAND || mode > MODE_PATROL)
        {
            return false;
        }
        setObjVar(droid, OBJVAR_MODE, mode);
        setObjVar(droid, OBJVAR_HOME, getLocation(droid));
        messageTo(droid, "handleApplyMode", null, 0, false);
        switch (mode)
        {
            case MODE_WANDER:
                sendSystemMessage(player, sid("stationed_mode_wander"));
                break;
            case MODE_GUARD:
                sendSystemMessage(player, sid("stationed_mode_guard"));
                break;
            case MODE_PATROL:
                sendSystemMessage(player, sid("stationed_mode_patrol"));
                break;
            default:
                sendSystemMessage(player, sid("stationed_mode_stand"));
                break;
        }
        return true;
    }

    /** Pushes the stored mode into the engine AI. Called by ai.stationed_droid. */
    public static void applyMode(obj_id droid) throws InterruptedException
    {
        if (!isStationed(droid))
        {
            return;
        }
        location home = hasObjVar(droid, OBJVAR_HOME) ? getLocationObjVar(droid, OBJVAR_HOME) : getLocation(droid);
        resetSpeed(droid);
        aiSetHomeLocation(droid, home);
        setHomeLocation(droid, home);
        // Movement for wander/patrol is driven by ai.stationed_droid (handleRoamTick); the engine
        // loiter only made these droids turn on the spot. The engine default is therefore STOP.
        removeObjVar(droid, "ai.loiterMinRange");
        removeObjVar(droid, "ai.loiterMaxRange");
        int mode = getMode(droid);
        setWantSawAttackTriggers(droid, mode == MODE_GUARD || mode == MODE_PATROL);
        ai_lib.setDefaultCalmBehavior(droid, ai_lib.BEHAVIOR_STOP);
        if (mode != MODE_WANDER && mode != MODE_PATROL && !ai_lib.isInCombat(droid) && getDistance(droid, home) > 1.5f)
        {
            pathTo(droid, home);
        }
    }

    // ---- speed -------------------------------------------------------------------------

    /** Full normal speed (what the droid used before casual roaming): used for fights and returning to the post. */
    public static void resetSpeed(obj_id droid) throws InterruptedException
    {
        setMovementPercent(droid, 1.0f);
        setMovementRun(droid);
    }

    // ---- ships ---------------------------------------------------------------------------

    /**
     * Called just before a ship with an interior is packed into its control device. A loose droid
     * left standing in a cell stays behind and falls to the ground when the ship is called again,
     * so every loose droid in the interior is returned to its owner's datapad instead (the owner
     * can set it loose again after calling the ship). A droid that cannot be stored (datapad full,
     * owner offline) is moved outside next to the ship, never left in the cell.
     * Returns the number of droids stored.
     */
    public static int stowDroidsAboard(obj_id ship) throws InterruptedException
    {
        if (!isIdValid(ship) || !exists(ship))
        {
            return 0;
        }
        obj_id[] contents = space_transition.getAllObjectsInPob(ship);
        if (contents == null)
        {
            return 0;
        }
        int stowed = 0;
        for (obj_id c : contents)
        {
            if (!isStationed(c))
            {
                continue;
            }
            obj_id owner = getLooseOwner(c);
            String name = getName(c);
            if (stowDroid(c))
            {
                stowed++;
                if (isIdValid(owner) && exists(owner))
                {
                    sendSystemMessageTestingOnly(owner, "Your droid " + name + " was returned to your datapad because the ship was packed. Call it again and set it loose when the ship is out.");
                }
            }
            else
            {
                ejectFromShip(c, ship, owner, name);
            }
        }
        return stowed;
    }

    /** Same result as pickUp (PCD back in the datapad, droid removed) but without the owner interaction checks. */
    private static boolean stowDroid(obj_id droid) throws InterruptedException
    {
        obj_id owner = getLooseOwner(droid);
        obj_id pcd = getParkedPcd(droid);
        if (!isIdValid(owner) || !exists(owner) || !isIdValid(pcd) || !exists(pcd))
        {
            return false;
        }
        obj_id datapad = utils.getPlayerDatapad(owner);
        if (!isIdValid(datapad) || !exists(datapad) || pet_lib.hasMaxStoredPetsOfType(owner, pet_lib.PET_TYPE_DROID))
        {
            return false;
        }
        stop(droid);
        if (!returnPcdToDatapad(droid, pcd, datapad))
        {
            return false;
        }
        pet_lib.savePetInfo(droid, pcd);
        unregisterDroid(owner, droid);
        utils.setScriptVar(droid, "stored", true);
        if (!destroyObject(droid))
        {
            LOG("stationed_droid", "stowDroid: failed to destroy droid " + droid + " after returning PCD " + pcd);
        }
        return true;
    }

    /** Fallback: puts the droid on the ground beside the ship (its new post) when it cannot be stored. */
    private static void ejectFromShip(obj_id droid, obj_id ship, obj_id owner, String name) throws InterruptedException
    {
        location shipLoc = getLocation(ship);
        if (shipLoc == null || isIdValid(shipLoc.cell) || isSpaceScene())
        {
            return;
        }
        location dest = new location(shipLoc.x + 3.0f, shipLoc.y, shipLoc.z + 3.0f, shipLoc.area);
        float h = getHeightAtLocation(dest.x, dest.z);
        if (h == h)
        {
            dest.y = h;
        }
        stop(droid);
        if (setLocation(droid, dest))
        {
            setObjVar(droid, OBJVAR_HOME, dest);
            if (isIdValid(owner) && exists(owner))
            {
                sendSystemMessageTestingOnly(owner, "Your droid " + name + " could not be stored (datapad full?) and was placed beside the ship.");
            }
        }
    }
}
