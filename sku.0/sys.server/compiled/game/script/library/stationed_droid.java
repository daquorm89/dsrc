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
    public static final float ROAM_STEP_MIN = 3.0f;
    public static final float ROAM_STEP_MAX = 9.0f;
    // Inside buildings / ship interiors hops are shorter (rooms are small).
    public static final float ROAM_STEP_MAX_INDOOR = 7.0f;
    // Largest height change allowed between two outdoor spots (avoids cliffs / ledges).
    public static final float ROAM_MAX_RISE = 1.5f;
    public static final int ROAM_PICK_TRIES = 8;
    // After this many ticks in a row without any visible spot, take one short blind hop toward
    // the post (safety valve in case line-of-sight tests are unreliable at a location).
    public static final int ROAM_BLIND_AFTER = 6;
    // Pause after reaching a spot; sometimes a longer idle.
    public static final float ROAM_PAUSE_MIN_SEC = 1.5f;
    public static final float ROAM_PAUSE_MAX_SEC = 5.0f;
    public static final float ROAM_LONG_PAUSE_CHANCE = 0.1f;
    public static final float ROAM_LONG_PAUSE_MIN_SEC = 6.0f;
    public static final float ROAM_LONG_PAUSE_MAX_SEC = 12.0f;
    // Retry soon when no reachable spot was found (narrow spaces) or the path was rejected.
    public static final float ROAM_RETRY_MIN_SEC = 1.5f;
    public static final float ROAM_RETRY_MAX_SEC = 3.5f;
    // Casual speed: mostly a walk at 60-100% of walking speed, now and then a slow jog.
    // Natural speed only. The client (RemoteCreatureController) slides a creature straight at the last
    // server position and RUNS at full run speed whenever it lags more than its own max walk speed
    // (metres) behind it. Scaling the walk speed down shrinks that threshold and made droids run in
    // bursts and slide sideways, and jogs (3-14 m/s) on 2-3.5 m hops made it worse. So: always 100 % walk.
    public static final float ROAM_WALK_PCT_MIN = 1.0f;
    public static final float ROAM_WALK_PCT_MAX = 1.0f;
    public static final float ROAM_JOG_CHANCE = 0.0f;

    /** Hops mostly continue roughly in the facing direction (+/- this many degrees), so direction changes are gentle. */
    public static final float ROAM_FORWARD_CONE_DEG = 75.0f;
    public static final int ROAM_FORWARD_TRIES = 5;
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
        return setLoose(droid, player, false);
    }

    /** quiet = true skips the "now loose" message (used when a droid is restored aboard a ship). */
    public static boolean setLoose(obj_id droid, obj_id player, boolean quiet) throws InterruptedException
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
        clearFollowState(droid);
        // ai.pet.OnDetach scheduled its own "default calm behaviour" message; apply our mode
        // now so that message finds our objvars instead of briefly letting the droid wander.
        applyMode(droid);
        if (!quiet)
        {
            sendSystemMessage(player, sid("stationed_now_loose"));
        }
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
    /**
     * The pet follow code leaves "ai.persistantFollowing" (and possibly formation flags) on a droid.
     * ai.ai.doDefaultCalmBehavior restarts a follow from them whenever the droid calms down, which walked loose
     * droids towards their owner and, because a hull room has no path to the owner, made the engine warp them onto
     * the owner (seen: droid at the owner's exact position, movementState 3 = follow, following = true).
     * A loose droid must never carry them.
     */
    public static void clearFollowState(obj_id droid) throws InterruptedException
    {
        if (!isIdValid(droid) || !exists(droid))
        {
            return;
        }
        if (hasObjVar(droid, "ai.persistantFollowing"))
        {
            removeObjVar(droid, "ai.persistantFollowing");
        }
        if (hasObjVar(droid, "ai.formationType"))
        {
            removeObjVar(droid, "ai.formationType");
        }
        if (hasObjVar(droid, "ai.inFormation"))
        {
            removeObjVar(droid, "ai.inFormation");
        }
    }

    public static void applyMode(obj_id droid) throws InterruptedException
    {
        if (!isStationed(droid))
        {
            return;
        }
        clearFollowState(droid);
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
        if (isIdValid(getPostShip(droid)) && recoverIfFallen(droid))
        {
            return;   // was out of place inside a hull: put back on the post, nothing to walk
        }
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
    //
    // A loose droid cannot stay in a cell while the hull is packed into its control device (it is
    // left behind and falls to the ground when the ship is called again). So when the hull is packed
    // the droid is STOWED: its PCD goes back to the owner's datapad and is locked there
    // (OBJVAR_ABOARD_SHIP), the droid object is destroyed, and one record per droid is written on
    // the SHIP (OBJVAR_SHIP_RECORDS: post cell + coordinates, mode, owner, PCD). When the ship is
    // called out again, ai.stationed_ship_restore recreates each droid from its PCD at the recorded
    // post and sets it loose again with the same mode, with no player action.
    // The PCD is always in the owner's datapad in between, so a failure at any point can never lose
    // a droid: the worst case is an unlocked droid in the datapad.

    // While the POB droid handling is being debugged, every stow / restore decision is also sent to the
    // owner as a system message ("[droid] ..."), so a test needs no log reading. Set to false when stable.
    public static final boolean DEBUG_MESSAGES = true;
    /** Shown in every [droid] message so the chat log proves which build the server runs. Bump on every change. */
    public static final String BUILD_TAG = "P18.18-1";

    public static void dbg(obj_id owner, String msg) throws InterruptedException
    {
        LOG("stationed_droid", msg);
        if (DEBUG_MESSAGES && isIdValid(owner) && exists(owner))
        {
            sendSystemMessageTestingOnly(owner, "[droid " + BUILD_TAG + "] " + msg);
        }
    }

    public static final String SCRIPT_SHIP_RESTORE = "ai.stationed_ship_restore";
    // On the PCD: obj_id of the packed ship the droid belongs to (blocks calling it by hand).
    public static final String OBJVAR_ABOARD_SHIP = "stationed.aboardShip";
    // On the ship: String[] of records, see encodeRecord.
    public static final String OBJVAR_SHIP_RECORDS = "stationed.aboard";
    // Restore is retried while the owner is not ready (seated, in combat, droid slots full).
    public static final float RESTORE_RETRY_SEC = 5.0f;
    public static final int RESTORE_MAX_TRIES = 24;
    // Time between creating the droid and setting it loose: the pet setup messages
    // (handleAddMaster, handleSetupPet) must have run first.
    public static final float RESTORE_FINISH_DELAY_SEC = 1.5f;

    // On the ship, while a droid is being restored (created, but not yet set loose again):
    // the droid, its record and the game time it was created. A droid in this state is an ordinary
    // follower pet, so a pack in that window must put it back (restowInFlight) instead of leaving it
    // in the cell, where it would fall through the hull the next time the ship is called out.
    public static final String OBJVAR_RESTORING_DROID = "stationed.restoring.droid";
    public static final String OBJVAR_RESTORING_RECORD = "stationed.restoring.record";
    public static final String OBJVAR_RESTORING_TIME = "stationed.restoring.time";
    // A restore marker older than this (seconds) is stale (its finish message was lost).
    public static final int RESTORING_STALE_SEC = 20;

    // record fields
    private static final int REC_PCD = 0;
    private static final int REC_OWNER = 1;
    private static final int REC_CELL = 2;
    private static final int REC_X = 3;
    private static final int REC_Y = 4;
    private static final int REC_Z = 5;
    private static final int REC_YAW = 6;
    private static final int REC_MODE = 7;
    private static final int REC_FIELDS = 8;

    private static String encodeRecord(obj_id pcd, obj_id owner, String cellName, location spot, float yaw, int mode)
    {
        return pcd.toString() + "|" + owner.toString() + "|" + cellName + "|" + spot.x + "|" + spot.y + "|" + spot.z + "|" + yaw + "|" + mode;
    }

    /** Returns the record's fields, or null when it is malformed. */
    private static String[] decodeRecord(String raw)
    {
        if (raw == null)
        {
            return null;
        }
        String[] f = raw.split("\\|", -1);
        return f.length == REC_FIELDS ? f : null;
    }

    private static obj_id parseId(String s)
    {
        try
        {
            return obj_id.getObjId(Long.parseLong(s));
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    public static String[] getShipRecords(obj_id ship) throws InterruptedException
    {
        if (!isIdValid(ship) || !hasObjVar(ship, OBJVAR_SHIP_RECORDS))
        {
            return new String[0];
        }
        String[] list = getStringArrayObjVar(ship, OBJVAR_SHIP_RECORDS);
        return list == null ? new String[0] : list;
    }

    private static void putShipRecords(obj_id ship, java.util.List<String> list) throws InterruptedException
    {
        if (list.isEmpty())
        {
            removeObjVar(ship, OBJVAR_SHIP_RECORDS);
        }
        else
        {
            setObjVar(ship, OBJVAR_SHIP_RECORDS, list.toArray(new String[0]));
        }
    }

    private static void addShipRecord(obj_id ship, obj_id pcd, String record) throws InterruptedException
    {
        java.util.List<String> list = new java.util.ArrayList<String>();
        String prefix = pcd.toString() + "|";
        for (String raw : getShipRecords(ship))
        {
            if (!raw.startsWith(prefix))
            {
                list.add(raw);
            }
        }
        list.add(record);
        putShipRecords(ship, list);
    }

    private static void removeShipRecord(obj_id ship, obj_id pcd) throws InterruptedException
    {
        java.util.List<String> list = new java.util.ArrayList<String>();
        String prefix = pcd.toString() + "|";
        boolean changed = false;
        for (String raw : getShipRecords(ship))
        {
            if (raw.startsWith(prefix))
            {
                changed = true;
            }
            else
            {
                list.add(raw);
            }
        }
        if (changed)
        {
            putShipRecords(ship, list);
        }
    }

    private static boolean shipHasRecordFor(obj_id ship, obj_id pcd) throws InterruptedException
    {
        String prefix = pcd.toString() + "|";
        for (String raw : getShipRecords(ship))
        {
            if (raw.startsWith(prefix))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * True while this PCD belongs to a packed ship (its droid is stowed there and comes back when the
     * ship is called out), which blocks calling the droid by hand. The lock releases itself when the
     * ship no longer exists or no longer holds a record for the PCD.
     */
    public static boolean isAboardLocked(obj_id pcd) throws InterruptedException
    {
        if (!isIdValid(pcd) || !hasObjVar(pcd, OBJVAR_ABOARD_SHIP))
        {
            return false;
        }
        obj_id ship = getObjIdObjVar(pcd, OBJVAR_ABOARD_SHIP);
        if (isIdValid(ship) && exists(ship) && shipHasRecordFor(ship, pcd))
        {
            return true;
        }
        removeObjVar(pcd, OBJVAR_ABOARD_SHIP);
        return false;
    }

    /**
     * Called just before a ship with an interior is packed into its control device. Every loose droid
     * in the interior is stowed (see above) and will be restored when the ship is called out. A droid
     * that cannot be stowed (owner offline, datapad refuses the PCD, no usable post) is moved outside
     * next to the ship instead, never left in the cell. Returns the number of droids stowed.
     */
    public static int stowDroidsAboard(obj_id ship) throws InterruptedException
    {
        if (!isIdValid(ship) || !exists(ship))
        {
            return 0;
        }
        // A droid that is only half restored (created, not yet loose) is not "stationed" yet.
        obj_id restowed = restowInFlightIfAny(ship, "the ship was packed");
        int stowed = isIdValid(restowed) ? 1 : 0;
        java.util.List<obj_id> aboard = findDroidsAboard(ship);
        if (isIdValid(restowed))
        {
            aboard.remove(restowed);
        }
        LOG("stationed_droid", "stowDroidsAboard: ship=" + ship + " candidates=" + aboard.size() + " inFlightRestowed=" + stowed);
        obj_id shipOwner = getOwner(ship);
        dbg(shipOwner, "stow: " + aboard.size() + " droid(s) found aboard before packing");
        for (obj_id c : aboard)
        {
            try
            {
                if (!isStationed(c))
                {
                    stowFollowerDroid(c);
                    continue;
                }
                obj_id owner = getLooseOwner(c);
                String name = getName(c);
                if (stowDroid(c, ship))
                {
                    stowed++;
                    if (isIdValid(owner) && exists(owner))
                    {
                        sendSystemMessageTestingOnly(owner, "Your droid " + name + " was stowed in your datapad while the ship is packed. It returns to its post when the ship is called out.");
                    }
                }
                else
                {
                    dbg(owner, "stow: droid " + name + " could not be stowed, ejecting it beside the ship");
                    ejectFromShip(c, ship, owner, name);
                }
            }
            catch (Exception e)
            {
                dbg(shipOwner, "stow: exception on droid " + c + ": " + e);
            }
        }
        return stowed;
    }

    /**
     * Safety net, run right AFTER the hull went into its control device: any droid still standing in
     * a cell (a pack that raced with a restore, a droid the pre-pack stow could not see) would be left
     * behind and fall through the hull when the ship is called out again. Stows what it finds the same
     * way stowDroidsAboard does; a droid that cannot be stowed is logged (it cannot be ejected from a
     * packed hull). Returns the number of droids stowed.
     */
    public static int sweepPackedShip(obj_id ship) throws InterruptedException
    {
        if (!isIdValid(ship) || !exists(ship))
        {
            return 0;
        }
        obj_id restowed = restowInFlightIfAny(ship, "the ship was packed");
        int stowed = isIdValid(restowed) ? 1 : 0;
        java.util.List<obj_id> aboard = findDroidsAboard(ship);
        if (isIdValid(restowed))
        {
            aboard.remove(restowed);
        }
        obj_id shipOwner = getOwner(ship);
        if (aboard.size() > 0)
        {
            dbg(shipOwner, "sweep: " + aboard.size() + " droid(s) still in the hull after packing");
        }
        for (obj_id c : aboard)
        {
            try
            {
                if (!isStationed(c))
                {
                    stowFollowerDroid(c);
                    stowed++;
                    continue;
                }
                obj_id owner = getLooseOwner(c);
                String name = getName(c);
                if (stowDroid(c, ship))
                {
                    stowed++;
                    LOG("stationed_droid", "sweepPackedShip: late stow of droid " + c + " from packed ship " + ship);
                    if (isIdValid(owner) && exists(owner))
                    {
                        sendSystemMessageTestingOnly(owner, "Your droid " + name + " was stowed in your datapad while the ship is packed. It returns to its post when the ship is called out.");
                    }
                }
                else
                {
                    dbg(owner, "sweep: droid " + name + " is left in the packed ship (could not be stowed)");
                }
            }
            catch (Exception e)
            {
                dbg(shipOwner, "sweep: exception on droid " + c + ": " + e);
            }
        }
        if (stowed > 0)
        {
            LOG("stationed_droid", "sweepPackedShip: ship=" + ship + " stowed=" + stowed);
        }
        return stowed;
    }

    /** True when the droid stands inside this ship (container chain first, cell location as a fallback). */
    private static boolean isAboard(obj_id droid, obj_id ship) throws InterruptedException
    {
        if (!isIdValid(droid) || !exists(droid))
        {
            return false;
        }
        if (space_transition.getContainingShip(droid) == ship)
        {
            return true;
        }
        return isCellOfShip(getLocation(droid), ship);
    }

    /**
     * Every droid standing in the ship's cells: the cell contents, plus the owner's registered loose
     * droids that are in the hull but were not listed (getContents does not always see all of them).
     * Both stationed droids and plain follower droids are returned.
     */
    private static java.util.List<obj_id> findDroidsAboard(obj_id ship) throws InterruptedException
    {
        java.util.List<obj_id> found = new java.util.ArrayList<obj_id>();
        obj_id[] contents = space_transition.getAllObjectsInPob(ship);
        if (contents != null)
        {
            for (obj_id c : contents)
            {
                if (!isIdValid(c) || !exists(c) || found.contains(c))
                {
                    continue;
                }
                if (isStationed(c) || (isMob(c) && !isPlayer(c) && pet_lib.isDroidPet(c)))
                {
                    found.add(c);
                }
            }
        }
        obj_id shipOwner = getOwner(ship);
        if (isIdValid(shipOwner))
        {
            for (obj_id d : getOwnedDroids(shipOwner))
            {
                if (!isIdValid(d) || !exists(d) || found.contains(d))
                {
                    continue;
                }
                // In the hull but not listed, OR already fallen out of it while its post is inside:
                // stowDroid files the record under the post, so a fallen droid is stowed too.
                if (isAboard(d, ship) || isPostInShip(d, ship))
                {
                    LOG("stationed_droid", "findDroidsAboard: droid " + d + " belongs to ship " + ship + " but was not in the cell contents (aboard=" + isAboard(d, ship) + ")");
                    found.add(d);
                }
            }
        }
        return found;
    }

    /**
     * A called-out (follower) droid left standing in a hull that is being packed: stored like any
     * other droid (back into its control device in the datapad). It does not come back on its own.
     */
    private static void stowFollowerDroid(obj_id droid) throws InterruptedException
    {
        obj_id master = getMaster(droid);
        LOG("stationed_droid", "stowFollowerDroid: storing follower droid " + droid + " master=" + master);
        pet_lib.storePet(droid, master);
    }

    /** True when the location is inside a cell of this ship (whether or not the ship is top level right now). */
    private static boolean isCellOfShip(location loc, obj_id ship) throws InterruptedException
    {
        if (loc == null || !isIdValid(loc.cell))
        {
            return false;
        }
        return getContainedBy(loc.cell) == ship || getTopMostContainer(loc.cell) == ship;
    }

    /**
     * Stows one droid: PCD back into the owner's datapad and locked, record written on the ship, droid
     * destroyed. The datapad droid cap is deliberately not applied here (the droid is not callable
     * while locked, and the number of loose droids is capped separately). Every step that can fail
     * runs before anything is written.
     */
    private static boolean stowDroid(obj_id droid, obj_id ship) throws InterruptedException
    {
        obj_id owner = getLooseOwner(droid);
        obj_id pcd = getParkedPcd(droid);
        if (!isIdValid(owner) || !exists(owner) || !isIdValid(pcd) || !exists(pcd))
        {
            dbg(owner, "stowDroid: droid " + droid + " not stowed: owner=" + owner + " ownerLoaded=" + (isIdValid(owner) && exists(owner)) + " pcd=" + pcd + " pcdLoaded=" + (isIdValid(pcd) && exists(pcd)));
            return false;
        }
        obj_id datapad = utils.getPlayerDatapad(owner);
        if (!isIdValid(datapad) || !exists(datapad))
        {
            dbg(owner, "stowDroid: droid " + droid + " not stowed: owner " + owner + " has no usable datapad");
            return false;
        }
        // The post is where the droid belongs (centre of its wandering); fall back to where it stands.
        location spot = hasObjVar(droid, OBJVAR_HOME) ? getLocationObjVar(droid, OBJVAR_HOME) : null;
        if (!isCellOfShip(spot, ship))
        {
            spot = getLocation(droid);
        }
        if (!isCellOfShip(spot, ship))
        {
            dbg(owner, "stowDroid: droid " + droid + " not stowed: neither its post nor its position is in a cell of ship " + ship);
            return false;
        }
        String cellName = getCellName(spot.cell);
        if (cellName == null || cellName.length() == 0 || cellName.indexOf('|') >= 0)
        {
            dbg(owner, "stowDroid: droid " + droid + " not stowed: unusable cell name '" + cellName + "' (cell " + spot.cell + ")");
            return false;
        }
        float yaw = getYaw(droid);
        String record = encodeRecord(pcd, owner, cellName, spot, yaw, getMode(droid));

        stop(droid);
        if (!returnPcdToDatapad(droid, pcd, datapad))
        {
            dbg(owner, "stowDroid: droid " + droid + " not stowed: the datapad refused PCD " + pcd);
            return false;
        }
        setObjVar(pcd, OBJVAR_ABOARD_SHIP, ship);
        addShipRecord(ship, pcd, record);
        if (!hasScript(ship, SCRIPT_SHIP_RESTORE))
        {
            attachScript(ship, SCRIPT_SHIP_RESTORE);
        }
        pet_lib.savePetInfo(droid, pcd);
        unregisterDroid(owner, droid);
        utils.setScriptVar(droid, "stored", true);
        if (!destroyObject(droid))
        {
            dbg(owner, "stowDroid: failed to destroy droid " + droid + " after returning PCD " + pcd);
        }
        return true;
    }

    // ---- restore: the ship is called out again --------------------------------------------

    private static final int RESTORE_DROPPED = 0;   // record dealt with without creating a droid (stale, or given up)
    private static final int RESTORE_CREATED = 1;   // droid created; finishAboardRestore completes it
    private static final int RESTORE_WAIT = 2;      // owner not loaded here: keep the record for a later unpack
    private static final int RESTORE_RETRY = 3;     // owner not ready (seated, in combat, droid slots full)
    public static final int FINISH_MAX_TRIES = 10;

    private static void removeRawRecord(obj_id ship, String raw) throws InterruptedException
    {
        java.util.List<String> list = new java.util.ArrayList<String>();
        for (String r : getShipRecords(ship))
        {
            if (!r.equals(raw))
            {
                list.add(r);
            }
        }
        putShipRecords(ship, list);
    }

    private static location readSpot(obj_id ship, String[] r, obj_id cell)
    {
        try
        {
            location shipLoc = getLocation(ship);
            if (shipLoc == null || shipLoc.area == null)
            {
                return null;
            }
            return new location(Float.parseFloat(r[REC_X]), Float.parseFloat(r[REC_Y]), Float.parseFloat(r[REC_Z]), shipLoc.area, cell);
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    /** The droid cannot come back: release the lock and tell the owner it is in the datapad. */
    private static void giveUpRestore(obj_id ship, obj_id pcd, obj_id owner, String why) throws InterruptedException
    {
        dbg(owner, "restore given up: pcd=" + pcd + " ship=" + ship + " reason=" + why);
        removeShipRecord(ship, pcd);
        if (isIdValid(pcd) && exists(pcd))
        {
            removeObjVar(pcd, OBJVAR_ABOARD_SHIP);
            if (isIdValid(owner) && exists(owner))
            {
                sendSystemMessageTestingOnly(owner, "Your droid " + getAssignedName(pcd) + " could not be returned to its post aboard the ship (" + why + "). It is in your datapad: call it and set it loose again.");
            }
        }
    }

    /**
     * One restore pass over a ship that is out on a planet. At most ONE droid is created per pass:
     * a freshly created droid is a normal pet until finishAboardRestore sets it loose, and the owner's
     * active-droid cap would destroy a second one. The script then runs the next pass.
     * Returns true when the caller should run another pass after RESTORE_RETRY_SEC.
     */
    public static boolean restoreDroidsAboard(obj_id ship, int attempt) throws InterruptedException
    {
        if (!isIdValid(ship) || !exists(ship) || isIdValid(getContainedBy(ship)) || isSpaceScene())
        {
            // packed, gone, or in space (loose droids cannot be set loose in space): records stay for a later unpack
            return false;
        }
        if (attempt == 0)
        {
            // A droid that fell out of the hull on this unpack goes back on its post first.
            recoverFallenDroids(ship);
        }
        // One droid at a time: while one is half restored its own finish step starts the next pass.
        // (Several chains can be running after quick pack/unpack cycles.)
        if (hasObjVar(ship, OBJVAR_RESTORING_DROID))
        {
            obj_id inFlight = getObjIdObjVar(ship, OBJVAR_RESTORING_DROID);
            int age = getGameTime() - (hasObjVar(ship, OBJVAR_RESTORING_TIME) ? getIntObjVar(ship, OBJVAR_RESTORING_TIME) : 0);
            if (isIdValid(inFlight) && exists(inFlight) && age <= RESTORING_STALE_SEC)
            {
                return false;
            }
            restowInFlightIfAny(ship, "its restore never finished");
        }
        boolean retry = false;
        boolean lastTry = attempt >= RESTORE_MAX_TRIES - 1;
        if (attempt == 0 && getShipRecords(ship).length > 0)
        {
            dbg(getOwner(ship), "restore: " + getShipRecords(ship).length + " stowed droid(s) to bring back");
        }
        for (String raw : getShipRecords(ship))
        {
            String[] r = decodeRecord(raw);
            if (r == null || !isIdValid(parseId(r[REC_PCD])) || !isIdValid(parseId(r[REC_OWNER])))
            {
                LOG("stationed_droid", "restore: dropping malformed record on ship " + ship + ": " + raw);
                removeRawRecord(ship, raw);
                continue;
            }
            int result = restoreOne(ship, raw, r, lastTry);
            if (result == RESTORE_CREATED)
            {
                return false;
            }
            if (result == RESTORE_RETRY)
            {
                retry = true;
            }
        }
        return retry;
    }

    private static int restoreOne(obj_id ship, String raw, String[] r, boolean lastTry) throws InterruptedException
    {
        obj_id pcd = parseId(r[REC_PCD]);
        obj_id owner = parseId(r[REC_OWNER]);
        if (!exists(owner))
        {
            return RESTORE_WAIT;
        }
        if (!exists(pcd) || !hasObjVar(pcd, OBJVAR_ABOARD_SHIP) || getObjIdObjVar(pcd, OBJVAR_ABOARD_SHIP) != ship)
        {
            // PCD destroyed, or the lock was released and the droid is in use: nothing to restore.
            removeShipRecord(ship, pcd);
            return RESTORE_DROPPED;
        }
        obj_id datapad = utils.getPlayerDatapad(owner);
        if (!isIdValid(datapad) || getContainedBy(pcd) != datapad)
        {
            giveUpRestore(ship, pcd, owner, "its control device is not in your datapad");
            return RESTORE_DROPPED;
        }
        obj_id cell = getCellId(ship, r[REC_CELL]);
        location spot = isIdValid(cell) ? readSpot(ship, r, cell) : null;
        if (spot == null)
        {
            giveUpRestore(ship, pcd, owner, "its post no longer exists");
            return RESTORE_DROPPED;
        }
        if (getPosture(owner) != POSTURE_UPRIGHT || ai_lib.isInCombat(owner) || pet_lib.hasMaxPets(owner, pet_lib.PET_TYPE_DROID))
        {
            if (lastTry)
            {
                giveUpRestore(ship, pcd, owner, "you were busy or had no free droid slot");
                return RESTORE_DROPPED;
            }
            return RESTORE_RETRY;
        }

        pet_lib.createPetFromData(pcd);
        obj_id droid = callable.getCDCallable(pcd);
        if (!isIdValid(droid) || !exists(droid))
        {
            if (lastTry)
            {
                giveUpRestore(ship, pcd, owner, "the droid could not be created");
                return RESTORE_DROPPED;
            }
            return RESTORE_RETRY;
        }
        // The droid exists again: the record and lock are spent whatever happens next, and the PCD is
        // an ordinary called-out PCD from here on.
        removeShipRecord(ship, pcd);
        removeObjVar(pcd, OBJVAR_ABOARD_SHIP);
        markRestoring(ship, droid, raw);
        stop(droid);
        boolean placed = setLocation(droid, spot);
        // A new pet's AI home is where the owner stands (outside the ship) and the pet setup queues a follow
        // behaviour towards the owner: either one can warp the droid out of the hull. Home goes on the post now and
        // the ship keeps the droid still until it is set loose (handleHoldRestoringDroid).
        aiSetHomeLocation(droid, spot);
        dictionary hold = new dictionary();
        hold.put("droid", droid);
        hold.put("record", raw);
        hold.put("n", 0);
        messageTo(ship, "handleHoldRestoringDroid", hold, RESTORE_HOLD_SEC, false);
        dbg(owner, "restore: created droid=" + droid + " for pcd=" + pcd + " on ship=" + ship + " cell=" + r[REC_CELL] + " setLocation=" + placed + " aboard=" + isAboard(droid, ship));
        dictionary d = new dictionary();
        d.put("droid", droid);
        d.put("owner", owner);
        d.put("record", raw);
        d.put("tries", 0);
        messageTo(ship, "handleFinishLooseDroid", d, RESTORE_FINISH_DELAY_SEC, false);
        return RESTORE_CREATED;
    }

    public static final float RESTORE_HOLD_SEC = 0.25f;
    public static final int RESTORE_HOLD_MAX = 40;   // 10 s

    /**
     * While a droid is being restored (created, not yet set loose): keep it still, keep its AI home on its post and
     * put it back on the post when anything moved it. Returns true while it should be called again.
     */
    public static boolean holdRestoringDroid(obj_id ship, obj_id droid, String raw) throws InterruptedException
    {
        if (!isIdValid(droid) || !exists(droid) || isStationed(droid) || !isIdValid(ship) || !exists(ship))
        {
            return false;   // set loose already (or gone): the normal watch takes over
        }
        if (isIdValid(getContainedBy(ship)) || isSpaceScene())
        {
            return false;
        }
        String[] r = decodeRecord(raw);
        obj_id cell = r == null ? null : getCellId(ship, r[REC_CELL]);
        location spot = isIdValid(cell) ? readSpot(ship, r, cell) : null;
        if (spot == null)
        {
            return false;
        }
        stop(droid);
        clearFollowState(droid);
        aiSetHomeLocation(droid, spot);
        if (!isAboard(droid, ship) || !isIdValid(getLocation(droid).cell) || !getLocation(droid).cell.equals(spot.cell))
        {
            dbg(getLooseOwnerOrMaster(droid), "restore: hold put droid " + droid + " back on its post (it was at " + getLocation(droid) + ")");
            setLocation(droid, spot);
        }
        return true;
    }

    /** The owner while a droid is not yet loose: its pet master. */
    private static obj_id getLooseOwnerOrMaster(obj_id droid) throws InterruptedException
    {
        obj_id owner = getLooseOwner(droid);
        return isIdValid(owner) ? owner : getMaster(droid);
    }

    /**
     * Second half of a restore, run RESTORE_FINISH_DELAY_SEC after the droid was created (the pet
     * setup messages have run by then): puts the droid back on its post, sets it loose again with its
     * saved mode. Returns true when it should be tried again shortly.
     */
    public static boolean finishAboardRestore(obj_id ship, obj_id droid, obj_id owner, String raw, int tries) throws InterruptedException
    {
        String[] r = decodeRecord(raw);
        if (r == null || !isIdValid(droid) || !exists(droid) || !isIdValid(owner) || !exists(owner) || !isIdValid(ship) || !exists(ship))
        {
            clearRestoringFor(ship, droid);
            return false;   // droid or owner gone: the PCD is already safe in the datapad
        }
        if (isIdValid(getContainedBy(ship)) || isSpaceScene())
        {
            // The ship was packed again before the droid was loose: it must not stay in the hull.
            restowInFlight(ship, droid, owner, raw, "the ship was packed again");
            return false;
        }
        String name = getName(droid);
        obj_id cell = getCellId(ship, r[REC_CELL]);
        location spot = isIdValid(cell) ? readSpot(ship, r, cell) : null;
        if (spot != null)
        {
            // The pet follow behaviour may have moved it towards the owner in the meantime.
            stop(droid);
            clearFollowState(droid);
            setLocation(droid, spot);
            aiSetHomeLocation(droid, spot);
            if (!isAboard(droid, ship))
            {
                // Never set a droid loose outside the hull: it would stand on the ground under the ship.
                dbg(owner, "restore: droid " + droid + " is not aboard ship " + ship + " after setLocation, try " + tries);
                if (tries < FINISH_MAX_TRIES - 1)
                {
                    return true;
                }
                restowInFlight(ship, droid, owner, raw, "it could not be placed back on its post");
                sendSystemMessageTestingOnly(owner, "Your droid " + name + " could not be placed back aboard the ship. It is in your datapad: call it and set it loose again.");
                return false;
            }
        }
        string_id why = getSetLooseBlocker(droid, owner);
        if (why != null)
        {
            if (tries < FINISH_MAX_TRIES - 1)
            {
                return true;
            }
            clearRestoringFor(ship, droid);
            sendSystemMessageTestingOnly(owner, "Your droid " + name + " is back aboard the ship but could not be set loose again. It is following you: set it loose again.");
            return false;
        }
        if (!setLoose(droid, owner, true))
        {
            clearRestoringFor(ship, droid);
            sendSystemMessageTestingOnly(owner, "Your droid " + name + " is back aboard the ship but could not be set loose again. It is following you: set it loose again.");
            return false;
        }
        int mode = MODE_STAND;
        try
        {
            mode = Integer.parseInt(r[REC_MODE]);
        }
        catch (NumberFormatException e)
        {
            mode = MODE_STAND;
        }
        if (mode < MODE_STAND || mode > MODE_PATROL)
        {
            mode = MODE_STAND;
        }
        if (spot != null)
        {
            setObjVar(droid, OBJVAR_HOME, spot);
            try
            {
                setYaw(droid, Float.parseFloat(r[REC_YAW]));
            }
            catch (NumberFormatException e)
            {
                // facing is cosmetic
            }
        }
        setObjVar(droid, OBJVAR_MODE, mode);
        applyMode(droid);
        messageTo(droid, "handleApplyMode", null, 2, false);
        clearRestoringFor(ship, droid);
        sendSystemMessageTestingOnly(owner, "Your droid " + name + " is back at its post aboard the ship.");
        return false;
    }

    // ---- restore bookkeeping ---------------------------------------------------------------

    private static void markRestoring(obj_id ship, obj_id droid, String raw) throws InterruptedException
    {
        setObjVar(ship, OBJVAR_RESTORING_DROID, droid);
        setObjVar(ship, OBJVAR_RESTORING_RECORD, raw);
        setObjVar(ship, OBJVAR_RESTORING_TIME, getGameTime());
    }

    private static void clearRestoring(obj_id ship) throws InterruptedException
    {
        removeObjVar(ship, OBJVAR_RESTORING_DROID);
        removeObjVar(ship, OBJVAR_RESTORING_RECORD);
        removeObjVar(ship, OBJVAR_RESTORING_TIME);
    }

    /** Clears the in-flight marker only when it belongs to this droid (a newer restore may own it now). */
    private static void clearRestoringFor(obj_id ship, obj_id droid) throws InterruptedException
    {
        if (!isIdValid(ship) || !hasObjVar(ship, OBJVAR_RESTORING_DROID))
        {
            return;
        }
        obj_id marked = getObjIdObjVar(ship, OBJVAR_RESTORING_DROID);
        if (!isIdValid(droid) || marked == null || marked.equals(droid))
        {
            clearRestoring(ship);
        }
    }

    /**
     * If a droid on this ship is half restored, puts it back (see restowInFlight). Returns that droid,
     * or null when nothing was in flight.
     */
    private static obj_id restowInFlightIfAny(obj_id ship, String why) throws InterruptedException
    {
        if (!isIdValid(ship) || !hasObjVar(ship, OBJVAR_RESTORING_DROID))
        {
            return null;
        }
        obj_id droid = getObjIdObjVar(ship, OBJVAR_RESTORING_DROID);
        String raw = hasObjVar(ship, OBJVAR_RESTORING_RECORD) ? getStringObjVar(ship, OBJVAR_RESTORING_RECORD) : null;
        obj_id owner = null;
        String[] r = decodeRecord(raw);
        if (r != null)
        {
            owner = parseId(r[REC_OWNER]);
        }
        if (!isIdValid(droid) || !exists(droid))
        {
            LOG("stationed_droid", "restowInFlightIfAny: in-flight droid " + droid + " of ship " + ship + " no longer exists; marker dropped");
            clearRestoring(ship);
            if (r != null)
            {
                obj_id pcd = parseId(r[REC_PCD]);
                if (isIdValid(pcd) && exists(pcd))
                {
                    // the PCD was linked to the lost droid: make it callable again, and leave it unlocked
                    callable.setCDCallable(pcd, null);
                    removeObjVar(pcd, OBJVAR_ABOARD_SHIP);
                }
            }
            return null;
        }
        restowInFlight(ship, droid, owner, raw, why);
        return droid;
    }

    /**
     * A droid that was recreated for a ship (so it is an ordinary follower pet until finishAboardRestore
     * sets it loose) has to go back into its PCD: the PCD is stored in the datapad again and locked, and
     * the record returns to the ship, so the droid comes back at its post on the next unpack. Never loses
     * the droid.
     */
    private static void restowInFlight(obj_id ship, obj_id droid, obj_id owner, String raw, String why) throws InterruptedException
    {
        dbg(owner, "restowInFlight: droid=" + droid + " ship=" + ship + " reason=" + why);
        clearRestoring(ship);
        String[] r = decodeRecord(raw);
        if (!isIdValid(owner) && r != null)
        {
            owner = parseId(r[REC_OWNER]);
        }
        if (isIdValid(droid) && exists(droid))
        {
            pet_lib.storePet(droid, owner);
        }
        if (r != null)
        {
            obj_id pcd = parseId(r[REC_PCD]);
            if (isIdValid(pcd) && exists(pcd))
            {
                setObjVar(pcd, OBJVAR_ABOARD_SHIP, ship);
                addShipRecord(ship, pcd, raw);
                if (!hasScript(ship, SCRIPT_SHIP_RESTORE))
                {
                    attachScript(ship, SCRIPT_SHIP_RESTORE);
                }
            }
        }
    }

    /** True when the droid's post (stationed.home) is a spot inside a cell of this ship. */
    public static boolean isPostInShip(obj_id droid, obj_id ship) throws InterruptedException
    {
        if (!isStationed(droid) || !hasObjVar(droid, OBJVAR_HOME))
        {
            return false;
        }
        return isCellOfShip(getLocationObjVar(droid, OBJVAR_HOME), ship);
    }

    /** Extra metres a droid may stray from its roam range before it counts as out of place. */
    public static final float POST_SLACK = 3.0f;
    /** Largest vertical difference to the post (ship decks) before a droid counts as out of place. */
    public static final float POST_MAX_RISE = 3.0f;
    /** Seconds between [droid] recovery messages of one droid (a failing recovery must not spam). */
    private static final int RECOVER_MSG_GAP_SEC = 30;

    /** The ship whose cell holds this droid's post, or null when the post is not inside a ship. */
    public static obj_id getPostShip(obj_id droid) throws InterruptedException
    {
        if (!isStationed(droid) || !hasObjVar(droid, OBJVAR_HOME))
        {
            return null;
        }
        location home = getLocationObjVar(droid, OBJVAR_HOME);
        if (home == null || !isIdValid(home.cell))
        {
            return null;
        }
        obj_id ship = getContainedBy(home.cell);
        if (!isIdValid(ship) || space_transition.getContainingShip(home.cell) != ship)
        {
            return null;
        }
        return ship;
    }

    /** How far from its post a droid inside a hull may legitimately be (roam range or fight range plus slack). */
    private static float postLeash(obj_id droid) throws InterruptedException
    {
        int mode = getMode(droid);
        float range = 0.0f;
        if (mode == MODE_PATROL || ai_lib.isInCombat(droid))
        {
            range = PATROL_MAX;
        }
        else if (mode == MODE_WANDER || mode == MODE_GUARD)
        {
            range = WANDER_MAX;
        }
        return range + POST_SLACK;
    }

    /**
     * True when the droid stands in the same cell as its post and within its leash. This is a
     * POSITION check on purpose: a droid that walked through a hull wall is still a child of the cell
     * (so a container check says it is aboard) but is no longer anywhere near its post.
     */
    public static boolean isHeldAtPost(obj_id droid, location home) throws InterruptedException
    {
        location cur = getLocation(droid);
        if (cur == null || home == null || !isIdValid(cur.cell))
        {
            return false;   // outside any cell: it is in the world, not in the hull
        }
        if (!cur.cell.equals(home.cell))
        {
            // Another room of the SAME ship is fine (the roam code makes it the new post); anything else is not.
            obj_id homeShip = space_transition.getContainingShip(home.cell);
            return isIdValid(homeShip) && space_transition.getContainingShip(cur.cell) == homeShip;
        }
        float dx = cur.x - home.x;
        float dz = cur.z - home.z;
        float max = postLeash(droid);
        return dx * dx + dz * dz <= max * max && Math.abs(cur.y - home.y) <= POST_MAX_RISE;
    }

    /**
     * A loose droid whose post is inside a ship that is out in the world, but which is not held at
     * that post (it is outside the hull, in another cell, or far from the post inside the same cell):
     * puts it back on its post. The post is never rewritten here. Returns true when it was moved.
     * Cheap when nothing is wrong, safe to call at any time.
     */
    public static boolean recoverIfFallen(obj_id droid) throws InterruptedException
    {
        if (isSpaceScene())
        {
            return false;
        }
        obj_id ship = getPostShip(droid);
        if (!isIdValid(ship) || !exists(ship) || isIdValid(getContainedBy(ship)))
        {
            return false;   // post is not in a ship that is out in the world right now
        }
        location home = getLocationObjVar(droid, OBJVAR_HOME);
        if (isHeldAtPost(droid, home))
        {
            return false;
        }
        location cur = getLocation(droid);
        obj_id owner = getLooseOwner(droid);
        String msg = "recoverIfFallen: droid " + droid + " is not at its post in ship " + ship + " (now " + cur + "); putting it back at " + home + "; owner is at " + (isIdValid(owner) && exists(owner) ? String.valueOf(getLocation(owner)) : "unknown")
            + "; ship is at " + getLocation(ship) + "; aiHome " + aiGetHomeLocation(droid) + "; movementState " + aiGetMovementState(droid) + "; following " + ai_lib.isFollowing(droid);
        int now = getGameTime();
        if (!utils.hasScriptVar(droid, "stationed.recoverMsg") || now - utils.getIntScriptVar(droid, "stationed.recoverMsg") >= RECOVER_MSG_GAP_SEC)
        {
            utils.setScriptVar(droid, "stationed.recoverMsg", now);
            dbg(getLooseOwner(droid), msg);
        }
        stop(droid);
        clearFollowState(droid);
        aiSetHomeLocation(droid, home);
        return setLocation(droid, home);
    }

    /**
     * May this loose droid start a fight with the target? A droid whose post is inside a ship only
     * fights targets that are inside the same ship, so it never chases anything out through the hull.
     */
    public static boolean mayEngage(obj_id droid, obj_id target) throws InterruptedException
    {
        obj_id ship = getPostShip(droid);
        if (!isIdValid(ship))
        {
            return true;
        }
        return isIdValid(target) && space_transition.getContainingShip(target) == ship;
    }

    /** True when this ship out in the world has droids to restore or recover (records, or posts aboard). */
    public static boolean needsRestorePass(obj_id ship) throws InterruptedException
    {
        if (!isIdValid(ship))
        {
            return false;
        }
        if (hasObjVar(ship, OBJVAR_SHIP_RECORDS) || hasObjVar(ship, OBJVAR_RESTORING_DROID))
        {
            return true;
        }
        obj_id owner = getOwner(ship);
        if (isIdValid(owner))
        {
            for (obj_id d : getOwnedDroids(owner))
            {
                if (isIdValid(d) && exists(d) && isPostInShip(d, ship))
                {
                    return true;
                }
            }
        }
        return false;
    }

    /** Recovers every registered droid of the ship owner that fell out of this ship (see recoverIfFallen). */
    public static int recoverFallenDroids(obj_id ship) throws InterruptedException
    {
        obj_id owner = getOwner(ship);
        if (!isIdValid(owner))
        {
            return 0;
        }
        int recovered = 0;
        for (obj_id d : getOwnedDroids(owner))
        {
            if (isIdValid(d) && exists(d) && recoverIfFallen(d))
            {
                messageTo(d, "handleApplyMode", null, 1, false);
                recovered++;
            }
        }
        return recovered;
    }

    /** Fallback: puts the droid on the ground beside the ship (its new post) when it cannot be stowed. */
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
                sendSystemMessageTestingOnly(owner, "Your droid " + name + " could not be stowed with the ship and was placed beside it.");
            }
        }
    }
}
