package script.ai;

import script.*;
import script.library.*;

/**
 * Behaviour of a loose ("set loose") droid. See script.library.stationed_droid for the
 * design and the objvars. Attached by stationed_droid.setLoose, replaces ai.pet.
 *
 * Deliberately does not use pet_lib.isDroidPet / pet_lib.hasContainer: both need the
 * "ai.pet" objvar, which ai.pet.OnDetach removes.
 */
public class stationed_droid extends script.base_script
{
    public stationed_droid()
    {
    }
    public static final String SCRIPTVAR_TICK = "stationed.tick";
    // Roam chain id (separate from the guard chain so neither cancels the other).
    public static final String SCRIPTVAR_ROAM_TICK = "stationed.roamTick";
    // Set while a hop is in progress; cleared by OnMovePathComplete / OnMovePathNotFound.
    public static final String SCRIPTVAR_MOVING = "stationed.moving";
    // Consecutive roam ticks that found no visible spot.
    public static final String SCRIPTVAR_NO_SPOT = "stationed.noSpot";
    public static final float MAX_MENU_DISTANCE = 15.0f;

    public int OnAttach(obj_id self) throws InterruptedException
    {
        messageTo(self, "handleApplyMode", null, 1, false);
        return SCRIPT_CONTINUE;
    }

    public int OnInitialize(obj_id self) throws InterruptedException
    {
        // After a server restart the engine may have dropped the master link; the droid is
        // persisted, the master id is only a pointer to the owner.
        obj_id owner = script.library.stationed_droid.getLooseOwner(self);
        if (isIdValid(owner))
        {
            setMaster(self, owner);
        }
        messageTo(self, "handleApplyMode", null, 2, false);
        return SCRIPT_CONTINUE;
    }

    // ---- radial menu ---------------------------------------------------------------

    public int OnObjectMenuRequest(obj_id self, obj_id player, menu_info mi) throws InterruptedException
    {
        if (player != script.library.stationed_droid.getLooseOwner(self))
        {
            return SCRIPT_CONTINUE;
        }
        int root = mi.addRootMenu(menu_info_types.SERVER_MENU1, new string_id("pet/pet_menu", "droid_options"));
        mi.addSubMenu(root, menu_info_types.SERVER_MENU2, script.library.stationed_droid.sid("stationed_stand"));
        mi.addSubMenu(root, menu_info_types.SERVER_MENU3, script.library.stationed_droid.sid("stationed_wander"));
        mi.addSubMenu(root, menu_info_types.SERVER_MENU4, script.library.stationed_droid.sid("stationed_guard"));
        mi.addSubMenu(root, menu_info_types.SERVER_MENU8, script.library.stationed_droid.sid("stationed_patrol"));
        obj_id pcd = script.library.stationed_droid.getParkedPcd(self);
        if (isIdValid(pcd) && exists(pcd))
        {
            if (isIdValid(utils.getInventoryContainer(pcd)))
            {
                mi.addSubMenu(root, menu_info_types.SERVER_MENU5, new string_id("pet/pet_menu", "menu_command_open"));
            }
            if (isIdValid(utils.getDatapad(pcd)))
            {
                mi.addSubMenu(root, menu_info_types.SERVER_MENU6, new string_id("pet/pet_menu", "menu_dpad"));
            }
        }
        mi.addSubMenu(root, menu_info_types.SERVER_MENU7, script.library.stationed_droid.sid("stationed_pick_up"));
        return SCRIPT_CONTINUE;
    }

    public int OnObjectMenuSelect(obj_id self, obj_id player, int item) throws InterruptedException
    {
        if (player != script.library.stationed_droid.getLooseOwner(self) || getDistance(self, player) > MAX_MENU_DISTANCE)
        {
            return SCRIPT_CONTINUE;
        }
        if (item == menu_info_types.SERVER_MENU2)
        {
            script.library.stationed_droid.setMode(self, player, script.library.stationed_droid.MODE_STAND);
        }
        else if (item == menu_info_types.SERVER_MENU3)
        {
            script.library.stationed_droid.setMode(self, player, script.library.stationed_droid.MODE_WANDER);
        }
        else if (item == menu_info_types.SERVER_MENU4)
        {
            script.library.stationed_droid.setMode(self, player, script.library.stationed_droid.MODE_GUARD);
        }
        else if (item == menu_info_types.SERVER_MENU8)
        {
            script.library.stationed_droid.setMode(self, player, script.library.stationed_droid.MODE_PATROL);
        }
        else if (item == menu_info_types.SERVER_MENU5 || item == menu_info_types.SERVER_MENU6)
        {
            obj_id pcd = script.library.stationed_droid.getParkedPcd(self);
            if (!isIdValid(pcd) || !exists(pcd))
            {
                return SCRIPT_CONTINUE;
            }
            obj_id container = (item == menu_info_types.SERVER_MENU5) ? utils.getInventoryContainer(pcd) : utils.getDatapad(pcd);
            if (isIdValid(container) && exists(container))
            {
                stop(self);
                setOwner(container, player);
                utils.requestContainerOpen(player, container);
            }
        }
        else if (item == menu_info_types.SERVER_MENU7)
        {
            script.library.stationed_droid.pickUp(self, player);
        }
        return SCRIPT_CONTINUE;
    }

    // ---- mode ----------------------------------------------------------------------

    public int handleApplyMode(obj_id self, dictionary params) throws InterruptedException
    {
        if (!script.library.stationed_droid.isStationed(self))
        {
            return SCRIPT_CONTINUE;
        }
        script.library.stationed_droid.applyMode(self);
        // Restart the guard scan; the tick id makes any older scan chain stop itself.
        int tick = utils.getIntScriptVar(self, SCRIPTVAR_TICK) + 1;
        utils.setScriptVar(self, SCRIPTVAR_TICK, tick);
        int mode = script.library.stationed_droid.getMode(self);
        dictionary d = new dictionary();
        d.put("tick", tick);
        if (mode == script.library.stationed_droid.MODE_GUARD || mode == script.library.stationed_droid.MODE_PATROL)
        {
            messageTo(self, "handleGuardTick", d, script.library.stationed_droid.GUARD_TICK_SEC, false);
        }
        // Always bump the roam chain id so a pending roam message of an older mode stops itself.
        utils.removeScriptVar(self, SCRIPTVAR_MOVING);
        utils.removeScriptVar(self, SCRIPTVAR_NO_SPOT);
        if (mode == script.library.stationed_droid.MODE_WANDER || mode == script.library.stationed_droid.MODE_PATROL)
        {
            scheduleRoam(self, 2.0f);
        }
        else
        {
            utils.setScriptVar(self, SCRIPTVAR_ROAM_TICK, utils.getIntScriptVar(self, SCRIPTVAR_ROAM_TICK) + 1);
        }
        return SCRIPT_CONTINUE;
    }

    // ---- wander / patrol movement --------------------------------------------------
    //
    // Short hops to spots the droid can SEE (canSee), so it never picks a point behind a wall;
    // a pause after every hop (OnMovePathComplete); a varying, mostly walking speed; and a quick
    // retry when no spot is found or the path is rejected. A watchdog message covers a hop that
    // never reports completion (the droid is then stopped and picks again).

    private boolean isRoamMode(obj_id self) throws InterruptedException
    {
        int mode = script.library.stationed_droid.getMode(self);
        return mode == script.library.stationed_droid.MODE_WANDER || mode == script.library.stationed_droid.MODE_PATROL;
    }

    /** (Re)starts the roam chain: any older pending roam message becomes stale and stops itself. */
    private void scheduleRoam(obj_id self, float delay) throws InterruptedException
    {
        int tick = utils.getIntScriptVar(self, SCRIPTVAR_ROAM_TICK) + 1;
        utils.setScriptVar(self, SCRIPTVAR_ROAM_TICK, tick);
        dictionary d = new dictionary();
        d.put("tick", tick);
        messageTo(self, "handleRoamTick", d, delay, false);
    }

    private float casualPause()
    {
        if (rand() < script.library.stationed_droid.ROAM_LONG_PAUSE_CHANCE)
        {
            return script.library.stationed_droid.ROAM_LONG_PAUSE_MIN_SEC + rand() * (script.library.stationed_droid.ROAM_LONG_PAUSE_MAX_SEC - script.library.stationed_droid.ROAM_LONG_PAUSE_MIN_SEC);
        }
        return script.library.stationed_droid.ROAM_PAUSE_MIN_SEC + rand() * (script.library.stationed_droid.ROAM_PAUSE_MAX_SEC - script.library.stationed_droid.ROAM_PAUSE_MIN_SEC);
    }

    private float quickRetry()
    {
        return script.library.stationed_droid.ROAM_RETRY_MIN_SEC + rand() * (script.library.stationed_droid.ROAM_RETRY_MAX_SEC - script.library.stationed_droid.ROAM_RETRY_MIN_SEC);
    }

    /** Picks this hop's speed (mostly a walk at 60-100%, sometimes a slow jog); returns the speed in m/s. */
    private float applyCasualSpeed(obj_id self) throws InterruptedException
    {
        float speed;
        if (rand() < script.library.stationed_droid.ROAM_JOG_CHANCE)
        {
            float pct = script.library.stationed_droid.ROAM_JOG_PCT_MIN + rand() * (script.library.stationed_droid.ROAM_JOG_PCT_MAX - script.library.stationed_droid.ROAM_JOG_PCT_MIN);
            setMovementRun(self);
            setMovementPercent(self, pct);
            speed = getRunSpeed(self) * pct;
        }
        else
        {
            float pct = script.library.stationed_droid.ROAM_WALK_PCT_MIN + rand() * (script.library.stationed_droid.ROAM_WALK_PCT_MAX - script.library.stationed_droid.ROAM_WALK_PCT_MIN);
            setMovementWalk(self);
            setMovementPercent(self, pct);
            speed = getWalkSpeed(self) * pct;
        }
        return speed < 0.5f ? 1.0f : speed;
    }

    private boolean sameCell(obj_id a, obj_id b)
    {
        if (!isIdValid(a) || !isIdValid(b))
        {
            return !isIdValid(a) && !isIdValid(b);
        }
        return a.equals(b);
    }

    /** True when the droid has a clear line of sight to (a point just above) the spot. */
    private boolean canReach(obj_id self, location spot) throws InterruptedException
    {
        return canSee(self, new location(spot.x, spot.y + 0.6f, spot.z, spot.area, spot.cell));
    }

    /**
     * A spot within range of the post and in clear view of the droid, or null when none was found.
     * Outdoors the spot must also be on level-ish ground.
     */
    private location pickRoamSpot(obj_id self, int mode) throws InterruptedException
    {
        location cur = getLocation(self);
        location home = hasObjVar(self, script.library.stationed_droid.OBJVAR_HOME) ? getLocationObjVar(self, script.library.stationed_droid.OBJVAR_HOME) : cur;
        if (!sameCell(cur.cell, home.cell))
        {
            // The droid ended up in another room: that is its post now.
            home = cur;
            setObjVar(self, script.library.stationed_droid.OBJVAR_HOME, cur);
        }
        float range = (mode == script.library.stationed_droid.MODE_PATROL) ? script.library.stationed_droid.PATROL_MAX : script.library.stationed_droid.WANDER_MAX;
        boolean indoors = isIdValid(cur.cell);
        float stepMax = indoors ? script.library.stationed_droid.ROAM_STEP_MAX_INDOOR : script.library.stationed_droid.ROAM_STEP_MAX;
        float stepMin = script.library.stationed_droid.ROAM_STEP_MIN;
        for (int i = 0; i < script.library.stationed_droid.ROAM_PICK_TRIES; i++)
        {
            double ang = rand() * 6.2831853d;
            float step = stepMin + rand() * (stepMax - stepMin);
            float x = cur.x + (float)Math.sin(ang) * step;
            float z = cur.z + (float)Math.cos(ang) * step;
            float hx = x - home.x;
            float hz = z - home.z;
            if (hx * hx + hz * hz > range * range)
            {
                continue;
            }
            float y = cur.y;
            if (!indoors)
            {
                y = getHeightAtLocation(x, z);
                if (y != y || Math.abs(y - cur.y) > script.library.stationed_droid.ROAM_MAX_RISE)
                {
                    continue;
                }
            }
            location spot = new location(x, y, z, cur.area, cur.cell);
            if (canReach(self, spot))
            {
                utils.removeScriptVar(self, SCRIPTVAR_NO_SPOT);
                return spot;
            }
        }
        // Nothing visible. After several misses in a row take one short blind hop toward the post.
        int misses = utils.getIntScriptVar(self, SCRIPTVAR_NO_SPOT) + 1;
        if (misses < script.library.stationed_droid.ROAM_BLIND_AFTER)
        {
            utils.setScriptVar(self, SCRIPTVAR_NO_SPOT, misses);
            return null;
        }
        utils.removeScriptVar(self, SCRIPTVAR_NO_SPOT);
        float dx = home.x - cur.x;
        float dz = home.z - cur.z;
        float len = (float)Math.sqrt(dx * dx + dz * dz);
        if (len < 1.5f)
        {
            return null;
        }
        float hop = Math.min(2.0f, len);
        float y = cur.y;
        if (!indoors)
        {
            y = getHeightAtLocation(cur.x + dx / len * hop, cur.z + dz / len * hop);
            if (y != y)
            {
                return null;
            }
        }
        return new location(cur.x + dx / len * hop, y, cur.z + dz / len * hop, cur.area, cur.cell);
    }

    /** Walks to a nearby visible spot, then pauses (wander and patrol). */
    public int handleRoamTick(obj_id self, dictionary params) throws InterruptedException
    {
        if (params == null || params.getInt("tick") != utils.getIntScriptVar(self, SCRIPTVAR_ROAM_TICK) || !script.library.stationed_droid.isStationed(self) || !isRoamMode(self))
        {
            return SCRIPT_CONTINUE;
        }
        if (ai_lib.isInCombat(self) || ai_lib.isAiDead(self) || isIncapacitated(self))
        {
            scheduleRoam(self, 4.0f);
            return SCRIPT_CONTINUE;
        }
        if (utils.hasScriptVar(self, SCRIPTVAR_MOVING))
        {
            // The previous hop never reported back (stuck): stop and choose again.
            stop(self);
            utils.removeScriptVar(self, SCRIPTVAR_MOVING);
        }
        location spot = pickRoamSpot(self, script.library.stationed_droid.getMode(self));
        if (spot == null)
        {
            scheduleRoam(self, quickRetry());
            return SCRIPT_CONTINUE;
        }
        if (getPosture(self) != POSTURE_UPRIGHT)
        {
            posture.stand(self);
        }
        float speed = applyCasualSpeed(self);
        // The engine AI home (not the post, which is the objvar) follows the droid for each hop:
        // ai.ai.OnMovePathNotFound warps a creature to its AI home, so even if that handler runs
        // before ours the "teleport" lands on the spot the droid already stands on.
        aiSetHomeLocation(self, getLocation(self));
        utils.setScriptVar(self, SCRIPTVAR_MOVING, 1);
        pathTo(self, spot);
        if (utils.hasScriptVar(self, SCRIPTVAR_MOVING))
        {
            // Watchdog: normally OnMovePathComplete / OnMovePathNotFound restarts the chain sooner.
            location cur = getLocation(self);
            float dx = spot.x - cur.x;
            float dz = spot.z - cur.z;
            float dist = (float)Math.sqrt(dx * dx + dz * dz);
            scheduleRoam(self, dist / speed * 1.6f + 4.0f);
        }
        return SCRIPT_CONTINUE;
    }

    /** Arrived: pause for a casual few seconds, then pick the next spot. */
    public int OnMovePathComplete(obj_id self) throws InterruptedException
    {
        if (script.library.stationed_droid.isStationed(self) && isRoamMode(self) && utils.hasScriptVar(self, SCRIPTVAR_MOVING))
        {
            utils.removeScriptVar(self, SCRIPTVAR_MOVING);
            scheduleRoam(self, casualPause());
        }
        return SCRIPT_CONTINUE;
    }

    /**
     * ai.ai.OnMovePathNotFound answers every failed path by warping the creature back to its home
     * location (the visible "teleport"). A loose droid just stays put and tries another spot.
     */
    public int OnMovePathNotFound(obj_id self) throws InterruptedException
    {
        if (!script.library.stationed_droid.isStationed(self) || ai_lib.isInCombat(self))
        {
            return SCRIPT_CONTINUE;
        }
        stop(self);
        if (isRoamMode(self))
        {
            utils.removeScriptVar(self, SCRIPTVAR_MOVING);
            scheduleRoam(self, quickRetry());
        }
        else
        {
            // Could not walk back to the post (stand / guard): the current spot becomes the post.
            setObjVar(self, script.library.stationed_droid.OBJVAR_HOME, getLocation(self));
        }
        return SCRIPT_OVERRIDE;
    }

    // ---- guard ---------------------------------------------------------------------

    public int handleGuardTick(obj_id self, dictionary params) throws InterruptedException
    {
        if (params == null || params.getInt("tick") != utils.getIntScriptVar(self, SCRIPTVAR_TICK) || !script.library.stationed_droid.isStationed(self) || (script.library.stationed_droid.getMode(self) != script.library.stationed_droid.MODE_GUARD && script.library.stationed_droid.getMode(self) != script.library.stationed_droid.MODE_PATROL))
        {
            return SCRIPT_CONTINUE;
        }
        if (!ai_lib.isInCombat(self) && !ai_lib.isAiDead(self) && !isIncapacitated(self))
        {
            obj_id owner = script.library.stationed_droid.getLooseOwner(self);
            obj_id[] near = getCreaturesInRange(self, script.library.stationed_droid.GUARD_RANGE);
            if (near != null)
            {
                for (obj_id target : near)
                {
                    if (isGuardTarget(self, owner, target))
                    {
                        script.library.stationed_droid.resetSpeed(self);
                        startCombat(self, target);
                        break;
                    }
                }
            }
        }
        messageTo(self, "handleGuardTick", params, script.library.stationed_droid.GUARD_TICK_SEC, false);
        return SCRIPT_CONTINUE;
    }

    /** Hostile non-player creatures only; players are handled through OnSawAttack. */
    private boolean isGuardTarget(obj_id self, obj_id owner, obj_id target) throws InterruptedException
    {
        if (!isIdValid(target) || target == self || target == owner || !exists(target))
        {
            return false;
        }
        if (isPlayer(target) || !isMob(target) || ai_lib.aiIsDead(target) || isIncapacitated(target) || isInvulnerable(target))
        {
            return false;
        }
        // Never start on anyone's pet or on other loose droids.
        if (pet_lib.hasMaster(target) || hasObjVar(target, script.library.stationed_droid.OBJVAR_OWNER))
        {
            return false;
        }
        if (!pvpCanAttack(self, target))
        {
            return false;
        }
        obj_id hate = getHateTarget(target);
        if (isIdValid(hate) && (hate == owner || hate == self))
        {
            return true;
        }
        // Guard only reacts when the owner or the droid is attacked; patrol also attacks
        // aggressive creatures on sight.
        return script.library.stationed_droid.getMode(self) == script.library.stationed_droid.MODE_PATROL && aiIsAggressive(target);
    }

    /** A droid always fights back when it is attacked, whatever its mode. */
    public int OnDefenderCombatAction(obj_id self, obj_id attacker, obj_id weapon, int combatResult) throws InterruptedException
    {
        if (isIdValid(attacker) && exists(attacker) && attacker != self && attacker != script.library.stationed_droid.getLooseOwner(self) && !ai_lib.isInCombat(self) && !ai_lib.isAiDead(self) && !isIncapacitated(self) && pvpCanAttack(self, attacker))
        {
            script.library.stationed_droid.resetSpeed(self);
            startCombat(self, attacker);
        }
        return SCRIPT_CONTINUE;
    }

    public int OnSawAttack(obj_id self, obj_id defender, obj_id[] attackers) throws InterruptedException
    {
        // Overrides ai.ai: a loose droid must not join social-group / lair defence on its own.
        int mode = script.library.stationed_droid.getMode(self);
        if ((mode != script.library.stationed_droid.MODE_GUARD && mode != script.library.stationed_droid.MODE_PATROL) || ai_lib.isInCombat(self) || attackers == null)
        {
            return SCRIPT_OVERRIDE;
        }
        obj_id owner = script.library.stationed_droid.getLooseOwner(self);
        if (defender != owner && defender != self)
        {
            return SCRIPT_OVERRIDE;
        }
        for (obj_id attacker : attackers)
        {
            if (isIdValid(attacker) && attacker != self && attacker != owner && pvpCanAttack(self, attacker))
            {
                script.library.stationed_droid.resetSpeed(self);
                startCombat(self, attacker);
                break;
            }
        }
        return SCRIPT_OVERRIDE;
    }

    // ---- combat aftermath ----------------------------------------------------------

    public int OnExitedCombat(obj_id self) throws InterruptedException
    {
        // Walk back to the post / resume wandering.
        messageTo(self, "handleApplyMode", null, 3, false);
        return SCRIPT_CONTINUE;
    }

    public int OnIncapacitated(obj_id self, obj_id attacker) throws InterruptedException
    {
        stop(self);
        messageTo(self, "handleRecover", null, script.library.stationed_droid.INCAP_RECOVER_SEC, false);
        return SCRIPT_CONTINUE;
    }

    public int handleRecover(obj_id self, dictionary params) throws InterruptedException
    {
        if (isIncapacitated(self) && !isDead(self))
        {
            int health = getMaxAttrib(self, HEALTH) / 4;
            setAttrib(self, HEALTH, health < 1 ? 1 : health);
        }
        messageTo(self, "handleApplyMode", null, 2, false);
        return SCRIPT_CONTINUE;
    }

    // ---- loss protection -----------------------------------------------------------

    public int OnDeath(obj_id self, obj_id killer, obj_id corpseId) throws InterruptedException
    {
        script.library.stationed_droid.rescuePcd(self);
        return SCRIPT_CONTINUE;
    }

    public int OnDestroy(obj_id self) throws InterruptedException
    {
        if (utils.hasScriptVar(self, "stored"))
        {
            // Normal pick up: PCD is already back in the datapad and the owner unregistered.
            return SCRIPT_CONTINUE;
        }
        script.library.stationed_droid.rescuePcd(self);
        script.library.stationed_droid.unregisterDroid(script.library.stationed_droid.getLooseOwner(self), self);
        return SCRIPT_CONTINUE;
    }
}
