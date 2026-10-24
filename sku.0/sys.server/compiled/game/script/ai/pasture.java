package script.ai;

import script.*;
import script.library.*;

/**
 * Behaviour of a creature pet that is out to pasture. See script.library.pasture_lib for the
 * design and the objvars. Attached by pasture_lib.finishPasture, replaces ai.pet.
 *
 * Roaming is script driven (short hops to visible spots near the pasture spot, long grazing
 * pauses), the same approach that made stationed droids walk naturally (P18.11-P18.16).
 */
public class pasture extends script.base_script
{
    public pasture()
    {
    }
    public static final String SCRIPTVAR_ROAM_TICK = "pasture.roamTick";
    public static final String SCRIPTVAR_MOVING = "pasture.moving";
    public static final String SCRIPTVAR_MATE_TICK = "pasture.mateTick";
    public static final float MAX_MENU_DISTANCE = 15.0f;
    public static final float TURN_FIRST_MIN_DEG = 25.0f;
    public static final float TURN_DEG_PER_SEC = 150.0f;
    public static final float TURN_WAIT_MAX_SEC = 1.6f;

    public int OnAttach(obj_id self) throws InterruptedException
    {
        messageTo(self, "handleApplyMode", null, 1, false);
        return SCRIPT_CONTINUE;
    }

    public int OnInitialize(obj_id self) throws InterruptedException
    {
        // After a restart the master link and the flags are re-applied; ai.ai.initializeScript runs
        // 2 s after the animal enters the world, so the mode is applied after it.
        obj_id owner = pasture_lib.getPastureOwner(self);
        if (isIdValid(owner))
        {
            setMaster(self, owner);
        }
        if (pasture_lib.PASTURE_INVULNERABLE)
        {
            setInvulnerable(self, true);
        }
        messageTo(self, "handleApplyMode", null, 4, false);
        return SCRIPT_CONTINUE;
    }

    // ---- radial menu -----------------------------------------------------------------

    public int OnObjectMenuRequest(obj_id self, obj_id player, menu_info mi) throws InterruptedException
    {
        if (player != pasture_lib.getPastureOwner(self))
        {
            return SCRIPT_CONTINUE;
        }
        int root = mi.addRootMenu(menu_info_types.SERVER_MENU1, pasture_lib.sid("pasture_options"));
        if (pasture_lib.getMode(self) == pasture_lib.MODE_STAY)
        {
            mi.addSubMenu(root, menu_info_types.SERVER_MENU2, pasture_lib.sid("pasture_roam"));
        }
        else
        {
            mi.addSubMenu(root, menu_info_types.SERVER_MENU3, pasture_lib.sid("pasture_stay"));
        }
        mi.addSubMenu(root, menu_info_types.SERVER_MENU4, pasture_lib.sid("pasture_info"));
        mi.addSubMenu(root, menu_info_types.SERVER_MENU5, pasture_lib.sid("pasture_retrieve"));
        return SCRIPT_CONTINUE;
    }

    public int OnObjectMenuSelect(obj_id self, obj_id player, int item) throws InterruptedException
    {
        if (player != pasture_lib.getPastureOwner(self) || getDistance(self, player) > MAX_MENU_DISTANCE)
        {
            return SCRIPT_CONTINUE;
        }
        if (item == menu_info_types.SERVER_MENU2)
        {
            pasture_lib.setMode(self, player, pasture_lib.MODE_ROAM);
        }
        else if (item == menu_info_types.SERVER_MENU3)
        {
            pasture_lib.setMode(self, player, pasture_lib.MODE_STAY);
        }
        else if (item == menu_info_types.SERVER_MENU4)
        {
            pasture_lib.say(player, pasture_lib.describe(self));
        }
        else if (item == menu_info_types.SERVER_MENU5)
        {
            pasture_lib.retrieve(self, player);
        }
        return SCRIPT_CONTINUE;
    }

    // ---- mode ------------------------------------------------------------------------

    public int handleApplyMode(obj_id self, dictionary params) throws InterruptedException
    {
        if (!pasture_lib.isPastured(self))
        {
            return SCRIPT_CONTINUE;
        }
        if (pasture_lib.PASTURE_INVULNERABLE)
        {
            setInvulnerable(self, true);
        }
        // No aggro / alert volumes: a pastured animal never starts a fight with passers-by. ai.ai re-creates
        // them on (re)initialisation and after combat (resetAi), so they are removed on every mode apply
        // (removeTriggerVolume acts on self).
        removeTriggerVolume(ai_lib.ALERT_VOLUME_NAME);
        removeTriggerVolume(ai_lib.AGGRO_VOLUME_NAME);
        pasture_lib.applyMode(self);
        startMateChain(self);
        utils.removeScriptVar(self, SCRIPTVAR_MOVING);
        if (pasture_lib.getMode(self) == pasture_lib.MODE_ROAM)
        {
            scheduleRoam(self, 3.0f + rand() * 5.0f);
        }
        else
        {
            // Bump the chain id so a pending roam message stops itself.
            utils.setScriptVar(self, SCRIPTVAR_ROAM_TICK, utils.getIntScriptVar(self, SCRIPTVAR_ROAM_TICK) + 1);
        }
        return SCRIPT_CONTINUE;
    }

    // ---- mating (pasture_lib.mateTick does the work; only adult females collect progress) ----

    private void startMateChain(obj_id self) throws InterruptedException
    {
        if (!pasture_lib.MATING_ENABLED)
        {
            return;
        }
        int tick = utils.getIntScriptVar(self, SCRIPTVAR_MATE_TICK) + 1;
        utils.setScriptVar(self, SCRIPTVAR_MATE_TICK, tick);
        dictionary d = new dictionary();
        d.put("tick", tick);
        messageTo(self, "handleMateTick", d, pasture_lib.MATE_TICK_SEC, false);
    }

    public int handleMateTick(obj_id self, dictionary params) throws InterruptedException
    {
        if (params == null || params.getInt("tick") != utils.getIntScriptVar(self, SCRIPTVAR_MATE_TICK) || !pasture_lib.isPastured(self))
        {
            return SCRIPT_CONTINUE;
        }
        try
        {
            pasture_lib.mateTick(self);
        }
        catch (Throwable t)
        {
            LOG("pasture", "handleMateTick: " + self + " failed: " + t);
        }
        messageTo(self, "handleMateTick", params, pasture_lib.MATE_TICK_SEC, false);
        return SCRIPT_CONTINUE;
    }

    // ---- roaming ---------------------------------------------------------------------

    private void scheduleRoam(obj_id self, float delay) throws InterruptedException
    {
        int tick = utils.getIntScriptVar(self, SCRIPTVAR_ROAM_TICK) + 1;
        utils.setScriptVar(self, SCRIPTVAR_ROAM_TICK, tick);
        dictionary d = new dictionary();
        d.put("tick", tick);
        messageTo(self, "handleRoamTick", d, delay, false);
    }

    private boolean isCurrentRoam(obj_id self, dictionary params) throws InterruptedException
    {
        return params != null && params.getInt("tick") == utils.getIntScriptVar(self, SCRIPTVAR_ROAM_TICK) && pasture_lib.isPastured(self) && pasture_lib.getMode(self) == pasture_lib.MODE_ROAM;
    }

    private float grazePause()
    {
        if (rand() < pasture_lib.ROAM_LONG_PAUSE_CHANCE)
        {
            return pasture_lib.ROAM_LONG_PAUSE_MIN_SEC + rand() * (pasture_lib.ROAM_LONG_PAUSE_MAX_SEC - pasture_lib.ROAM_LONG_PAUSE_MIN_SEC);
        }
        return pasture_lib.ROAM_PAUSE_MIN_SEC + rand() * (pasture_lib.ROAM_PAUSE_MAX_SEC - pasture_lib.ROAM_PAUSE_MIN_SEC);
    }

    private float quickRetry()
    {
        return pasture_lib.ROAM_RETRY_MIN_SEC + rand() * (pasture_lib.ROAM_RETRY_MAX_SEC - pasture_lib.ROAM_RETRY_MIN_SEC);
    }

    private boolean sameCell(obj_id a, obj_id b)
    {
        if (!isIdValid(a) || !isIdValid(b))
        {
            return !isIdValid(a) && !isIdValid(b);
        }
        return a.equals(b);
    }

    /** A visible spot near the pasture spot, or null. Outdoors the spot must be on level-ish ground. */
    private location pickRoamSpot(obj_id self) throws InterruptedException
    {
        location cur = getLocation(self);
        location home = pasture_lib.getHome(self);
        if (cur == null || home == null)
        {
            return null;
        }
        if (!sameCell(cur.cell, home.cell))
        {
            // Wandered into another room or out of a building: walk back towards the pasture spot.
            return home;
        }
        float range = pasture_lib.getRoamRange(self);
        boolean indoors = isIdValid(cur.cell);
        float stepMax = indoors ? pasture_lib.ROAM_STEP_MAX_INDOOR : pasture_lib.ROAM_STEP_MAX;
        float stepMin = pasture_lib.ROAM_STEP_MIN;
        for (int i = 0; i < pasture_lib.ROAM_PICK_TRIES; i++)
        {
            double ang = rand() * 6.2831853d;
            if (i < pasture_lib.ROAM_FORWARD_TRIES)
            {
                ang = Math.toRadians(getYaw(self) + (rand() * 2.0f - 1.0f) * pasture_lib.ROAM_FORWARD_CONE_DEG);
            }
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
                if (y != y || Math.abs(y - cur.y) > pasture_lib.ROAM_MAX_RISE)
                {
                    continue;
                }
            }
            location spot = new location(x, y, z, cur.area, cur.cell);
            if (canSee(self, new location(spot.x, spot.y + 0.6f, spot.z, spot.area, spot.cell)))
            {
                return spot;
            }
        }
        // Nothing visible: drift back towards the pasture spot if it is far away.
        float dx = home.x - cur.x;
        float dz = home.z - cur.z;
        if (dx * dx + dz * dz > range * range)
        {
            return home;
        }
        return null;
    }

    public int handleRoamTick(obj_id self, dictionary params) throws InterruptedException
    {
        if (!isCurrentRoam(self, params))
        {
            return SCRIPT_CONTINUE;
        }
        if (ai_lib.isInCombat(self) || ai_lib.isAiDead(self) || isIncapacitated(self))
        {
            scheduleRoam(self, 5.0f);
            return SCRIPT_CONTINUE;
        }
        if (utils.hasScriptVar(self, SCRIPTVAR_MOVING))
        {
            // The previous step never reported back: stop and choose again.
            stop(self);
            utils.removeScriptVar(self, SCRIPTVAR_MOVING);
        }
        location spot = pickRoamSpot(self);
        if (spot == null)
        {
            scheduleRoam(self, quickRetry());
            return SCRIPT_CONTINUE;
        }
        if (getPosture(self) != POSTURE_UPRIGHT)
        {
            posture.stand(self);
        }
        // Turn first: the server snaps the facing while the client turns gradually (sideways sliding).
        location from = getLocation(self);
        float bearing = (float)Math.toDegrees(Math.atan2(spot.x - from.x, spot.z - from.z));
        float delta = bearing - getYaw(self);
        while (delta > 180.0f) delta -= 360.0f;
        while (delta < -180.0f) delta += 360.0f;
        float absDelta = Math.abs(delta);
        if (absDelta <= TURN_FIRST_MIN_DEG)
        {
            startStep(self, spot);
            return SCRIPT_CONTINUE;
        }
        stop(self);
        faceTo(self, spot);
        dictionary d = new dictionary();
        d.put("tick", utils.getIntScriptVar(self, SCRIPTVAR_ROAM_TICK));
        d.put("spot", spot);
        messageTo(self, "handleRoamGo", d, Math.min(TURN_WAIT_MAX_SEC, 0.25f + absDelta / TURN_DEG_PER_SEC), false);
        return SCRIPT_CONTINUE;
    }

    public int handleRoamGo(obj_id self, dictionary params) throws InterruptedException
    {
        if (!isCurrentRoam(self, params) || ai_lib.isInCombat(self) || isIncapacitated(self))
        {
            return SCRIPT_CONTINUE;
        }
        location spot = params.getLocation("spot");
        if (spot == null)
        {
            scheduleRoam(self, quickRetry());
            return SCRIPT_CONTINUE;
        }
        startStep(self, spot);
        return SCRIPT_CONTINUE;
    }

    private void startStep(obj_id self, location spot) throws InterruptedException
    {
        setMovementWalk(self);
        setMovementPercent(self, 1.0f);
        float speed = getWalkSpeed(self);
        if (speed < 0.5f)
        {
            speed = 1.0f;
        }
        // ai.ai.OnMovePathNotFound warps a creature to its AI home: keep that on the current spot.
        aiSetHomeLocation(self, getLocation(self));
        pasture_lib.clearFollowState(self);
        utils.setScriptVar(self, SCRIPTVAR_MOVING, 1);
        pathTo(self, spot);
        if (utils.hasScriptVar(self, SCRIPTVAR_MOVING))
        {
            // Watchdog in case neither OnMovePathComplete nor OnMovePathNotFound arrives.
            location cur = getLocation(self);
            float dx = spot.x - cur.x;
            float dz = spot.z - cur.z;
            scheduleRoam(self, (float)Math.sqrt(dx * dx + dz * dz) / speed * 1.6f + 4.0f);
        }
    }

    public int OnMovePathComplete(obj_id self) throws InterruptedException
    {
        if (pasture_lib.isPastured(self) && utils.hasScriptVar(self, SCRIPTVAR_MOVING))
        {
            utils.removeScriptVar(self, SCRIPTVAR_MOVING);
            if (pasture_lib.getMode(self) == pasture_lib.MODE_ROAM)
            {
                scheduleRoam(self, grazePause());
            }
        }
        return SCRIPT_CONTINUE;
    }

    /** ai.ai answers a failed path by warping the creature home; a pastured animal just tries again. */
    public int OnMovePathNotFound(obj_id self) throws InterruptedException
    {
        if (!pasture_lib.isPastured(self))
        {
            return SCRIPT_CONTINUE;
        }
        stop(self);
        utils.removeScriptVar(self, SCRIPTVAR_MOVING);
        if (pasture_lib.getMode(self) == pasture_lib.MODE_ROAM)
        {
            scheduleRoam(self, quickRetry());
        }
        return SCRIPT_OVERRIDE;
    }

    // ---- fighting: only in self-defence, downed but never killed ----------------------

    public int OnSawAttack(obj_id self, obj_id defender, obj_id[] attackers) throws InterruptedException
    {
        // Overrides ai.ai: a pastured animal does not join social-group / lair defence.
        return SCRIPT_OVERRIDE;
    }

    /** A pastured animal fights back when it is attacked (it never starts a fight itself). */
    public int OnDefenderCombatAction(obj_id self, obj_id attacker, obj_id weapon, int combatResult) throws InterruptedException
    {
        if (isIdValid(attacker) && exists(attacker) && attacker != self && attacker != pasture_lib.getPastureOwner(self) && !ai_lib.isInCombat(self) && !ai_lib.isAiDead(self) && !isIncapacitated(self) && pvpCanAttack(self, attacker) && pasture_lib.mayEngage(self, attacker))
        {
            utils.setScriptVar(self, SCRIPTVAR_ROAM_TICK, utils.getIntScriptVar(self, SCRIPTVAR_ROAM_TICK) + 1);
            utils.removeScriptVar(self, SCRIPTVAR_MOVING);
            setMovementRun(self);
            startCombat(self, attacker);
        }
        return SCRIPT_CONTINUE;
    }

    public int OnExitedCombat(obj_id self) throws InterruptedException
    {
        messageTo(self, "handleApplyMode", null, 3, false);
        return SCRIPT_CONTINUE;
    }

    public int OnIncapacitated(obj_id self, obj_id attacker) throws InterruptedException
    {
        stop(self);
        utils.setScriptVar(self, SCRIPTVAR_ROAM_TICK, utils.getIntScriptVar(self, SCRIPTVAR_ROAM_TICK) + 1);
        messageTo(self, "handleRecover", null, pasture_lib.INCAP_RECOVER_SEC, false);
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

    // ---- loss protection -------------------------------------------------------------

    public int OnDeath(obj_id self, obj_id killer, obj_id corpseId) throws InterruptedException
    {
        pasture_lib.rescuePcd(self);
        return SCRIPT_CONTINUE;
    }

    public int OnDestroy(obj_id self) throws InterruptedException
    {
        if (utils.hasScriptVar(self, "stored"))
        {
            // Normal retrieve: PCD is already back in the datapad and the owner unregistered.
            return SCRIPT_CONTINUE;
        }
        pasture_lib.rescuePcd(self);
        pasture_lib.unregisterAnimal(pasture_lib.getPastureOwner(self), self);
        return SCRIPT_CONTINUE;
    }
}
